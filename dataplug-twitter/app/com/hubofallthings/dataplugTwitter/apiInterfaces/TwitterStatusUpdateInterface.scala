/*
 * Copyright (C) 2017-2019 Dataswift Ltd - All Rights Reserved
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Written by Augustinas Markevicius <augustinas.markevicius@dataswift.io> 2, 2017
 */

package com.hubofallthings.dataplugTwitter.apiInterfaces

import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.google.inject.Inject
import com.hubofallthings.dataplug.actors.Errors.SourceApiCommunicationException
import com.hubofallthings.dataplug.apiInterfaces.DataPlugContentUploader
import com.hubofallthings.dataplug.apiInterfaces.authProviders.RequestAuthenticatorOAuth1
import com.hubofallthings.dataplug.apiInterfaces.models.{ ApiEndpointCall, ApiEndpointMethod, DataPlugNotableShareRequest }
import com.hubofallthings.dataplug.services.UserService
import com.hubofallthings.dataplug.utils.Mailer
import com.hubofallthings.dataplugTwitter.models.TwitterStatusUpdate
import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.impl.providers.oauth1.TwitterProvider
import play.api.Logger
import play.api.http.Status._
import play.api.libs.json.Json
import play.api.libs.ws.{ WSClient, WSResponse }
import play.api.mvc.MultipartFormData.FilePart

import scala.concurrent.{ ExecutionContext, Future }

class TwitterStatusUpdateInterface @Inject() (
    val wsClient: WSClient,
    val userService: UserService,
    val authInfoRepository: AuthInfoRepository,
    val mailer: Mailer,
    val provider: TwitterProvider) extends DataPlugContentUploader with RequestAuthenticatorOAuth1 {

  protected val logger: Logger = Logger("TwitterStatusUpdateInterface")
  val namespace: String = "twitter"
  val endpoint: String = "status_update"

  val defaultApiEndpoint = ApiEndpointCall(
    "https://api.twitter.com",
    "/1.1/statuses/[action].json",
    ApiEndpointMethod.Post("Post", ""),
    Map(),
    Map(),
    Map("Content-Type" -> "application/json"),
    Some(Map()))

  val mediaShareApiEndpoint = ApiEndpointCall(
    "https://upload.twitter.com",
    "/1.1/media/upload.json",
    ApiEndpointMethod.Post("Post", ""),
    Map("command" -> "INIT", "total_bytes" -> "10240", "media_type" -> "image/jpeg"),
    Map(),
    Map("Content-Type" -> "application/x-www-form-urlencoded"),
    Some(Map()))

  /*
   * Uploads media from a provided URL to twitter by ascynhronously downloading a file and uploading
   * it directly to twitter using a Multipart-Form
   *
   * @param url URL of the file to be uploaded to twitter
   * @param hatAddress address of the HAT to upload on behalf of
   *
   * @return Twitter media_id_string to be used when embedding media in tweets
   */
  def uploadUrlMedia(url: String, hatAddress: String)(implicit ec: ExecutionContext): Future[String] = {

    // Step 1: Download from url
    // Step 2: Get user authentication details, sign request
    // Step 3: Upload the content, return media ID

    val eventualFileBody: Future[Source[ByteString, _]] = wsClient.url(url).stream().map(_.bodyAsSource)
    val eventualUser = userService.retrieve(LoginInfo("hatlogin", hatAddress)).map(_.get)
    val eventualAuthInfo = eventualUser flatMap { user =>
      val providerLoginInfo = user.linkedUsers.find(_.providerId == provider.id).get
      authInfoRepository.find[AuthInfoType](providerLoginInfo.loginInfo).map(_.get)
    }
    // Using existential types in uploading media file parts
    import scala.language.existentials
    val eventualMediaId: Future[String] = for {
      body <- eventualFileBody
      authInfo <- eventualAuthInfo
      result <- wsClient.url("https://upload.twitter.com/1.1/media/upload.json")
        .sign(oauth1service.sign(authInfo))
        .post(Source(FilePart("media", "filename", None, body) :: List()))
    } yield {
      result.status match {
        case OK =>
          logger.info(s"Got media ID: ${result.json \ "media_id_string"}")
          (result.json \ "media_id_string").get.as[String]
        case status =>
          logger.error(s"Unexpected response from upload (status code $status): ${result.body}")
          throw SourceApiCommunicationException(s"Unexpected response from twitter (status code $status): ${result.body}")
      }
    }

    eventualMediaId
  }

  def post(hatAddress: String, content: DataPlugNotableShareRequest)(implicit ec: ExecutionContext): Future[TwitterStatusUpdate] = {
    logger.info(s"Posting new tweet for $hatAddress")

    val maybeMediaLink = content.photo map { link =>
      uploadUrlMedia(link, hatAddress)
    }

    val eventualMaybeLink = maybeMediaLink.map(link => link.map(Some(_))).getOrElse(Future.successful(None))

    eventualMaybeLink flatMap { maybeLink =>

      val requestParamsWithQueryParams = defaultApiEndpoint.copy(
        queryParameters = Map("status" -> content.message, "media_ids" -> maybeLink.getOrElse("")),
        pathParameters = Map("action" -> "update"))

      val authenticatedFetchParameters = authenticateRequest(requestParamsWithQueryParams, hatAddress)

      authenticatedFetchParameters flatMap { requestParams =>
        buildRequest(requestParams) flatMap { result =>
          result.status match {
            case OK =>
              Future.successful(Json.parse(result.body).as[TwitterStatusUpdate])
            case status =>
              Future.failed(SourceApiCommunicationException(s"Unexpected response from twitter (status code $status): ${result.body}"))
          }
        }
      }
    }

  }

  def delete(hatAddress: String, providerId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    logger.info(s"Deleting tweet $providerId for $hatAddress.")

    val fetchParams = defaultApiEndpoint.copy(
      pathParameters = Map("action" -> "destroy", "id" -> providerId),
      path = "/1.1/statuses/[action]/[id].json")

    val authenticatedFetchParams = authenticateRequest(fetchParams, hatAddress)

    authenticatedFetchParams flatMap { requestParams =>
      buildRequest(requestParams) flatMap { result =>
        result.status match {
          case OK =>
            Future.successful(())
          case status =>
            Future.failed(SourceApiCommunicationException(
              s"Unexpected response from twitter (status code $status): ${result.body}"))
        }
      }
    }
  }

  override protected def buildRequest(params: ApiEndpointCall)(implicit ec: ExecutionContext): Future[WSResponse] =
    super[RequestAuthenticatorOAuth1].buildRequest(params)
}
