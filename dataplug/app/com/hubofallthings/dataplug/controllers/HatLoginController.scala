/*
 * Copyright (C) 2016-2019 Dataswift Ltd - All Rights Reserved
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Written by Andrius Aucinas <andrius.aucinas@dataswift.io>, 10 2016
 */

package com.hubofallthings.dataplug.controllers

import com.hubofallthings.dataplug.services.{ HatTokenService, UserService }
import com.hubofallthings.dataplug.utils.{ JwtPhataAuthenticatedAction, JwtPhataAwareAction, MailService, PhataAuthenticationEnvironment, SilhouettePhataAuthenticationController }
import javax.inject.Inject
import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.util.Clock
import com.mohiva.play.silhouette.api.{ LoginEvent, Silhouette }
import com.mohiva.play.silhouette.impl.exceptions.IdentityNotFoundException
import com.mohiva.play.silhouette.impl.providers.SocialProviderRegistry
import com.nimbusds.jwt.SignedJWT
import play.api.Configuration
import play.api.data.Forms._
import org.joda.time.DateTime
import play.api.Logger
import play.api.data.Form
import play.api.libs.ws.WSClient
import play.api.mvc._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.Try

class HatLoginController @Inject() (
    components: ControllerComponents,
    mailService: MailService,
    silhouette: Silhouette[PhataAuthenticationEnvironment],
    wsClient: WSClient,
    configuration: Configuration,
    clock: Clock,
    userService: UserService,
    hatTokenService: HatTokenService,
    socialProviderRegistry: SocialProviderRegistry,
    tokenUserAwareAction: JwtPhataAwareAction,
    dataPlugViewSet: DataPlugViewSet,
    tokenUserAuthenticatedAction: JwtPhataAuthenticatedAction)(
    implicit
    ec: ExecutionContext) extends SilhouettePhataAuthenticationController(components, silhouette, clock, configuration) {

  val hatProtocol = {
    configuration.getOptional[Boolean]("provisioning.hatSecure") match {
      case Some(true)  => "https://"
      case Some(false) => "http://"
      case _           => "https://"
    }
  }

  val logger = Logger(this.getClass)

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // HAT Login

  def authHat(redirect: Option[String]): Action[AnyContent] = tokenUserAwareAction.async { implicit request =>
    logger.info(s"logging in user ${request.maybeUser}")
    val token = request.getQueryString("token").get

    def tokenIssueTime(token: String) = Try(SignedJWT.parse(token)).map(t => new DateTime(t.getJWTClaimsSet.getIssueTime)).get

    val authResult = request.maybeUser match {
      case Some(user) => for {
        _ <- hatTokenService.save(user.userId, token, tokenIssueTime(token))
        authenticator <- env.authenticatorService.create(user.loginInfo)
        cookie <- env.authenticatorService.init(authenticator)
        result <- env.authenticatorService.embed(cookie, Redirect(dataPlugViewSet.indexRedirect))
      } yield {
        env.eventBus.publish(LoginEvent(user, request))
        val session = redirect.map(r => request.session + ("redirect" -> r))
          .getOrElse(request.session)
        result.withSession(session)
      }
      case None => Future.failed(new IdentityNotFoundException("Couldn't find user"))
    }

    authResult.recover {
      case e: ProviderException =>
        val signinHatForm = Form("hataddress" -> nonEmptyText)
        logger.warn("Failed to login - HAT App version outdated. Please update and try again.", e)
        Ok(dataPlugViewSet.signIn(signinHatForm, Some("Failed to login - HAT App version outdated. Please update and try again.")))
    }
  }

  val signinHatForm = Form("hataddress" -> nonEmptyText)

  def signinHat: Action[AnyContent] = Action.async { implicit request =>
    signinHatForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(dataPlugViewSet.signIn(signinHatForm, None))),
      address => {
        val hatHost = address.stripPrefix("http://").stripPrefix("https://").replaceAll("[^A-Za-z0-9.:]", "")

        val redirectUrl = routes.HatLoginController.authHat(None)
          .absoluteURL(configuration.getOptional[Boolean]("service.secure").getOrElse(false))

        val hatUri = wsClient.url(s"$hatProtocol$hatHost/hatlogin")
          .withQueryStringParameters("name" -> configuration.get[String]("service.name"), "redirect" -> redirectUrl)

        val hatNameCookieAge = 90.days

        val result = Redirect(hatUri.uri.toString)
          .withCookies(Cookie("hatname", address, maxAge = Some(hatNameCookieAge.toSeconds.toInt)))
        Future.successful(result)

      })
  }

}