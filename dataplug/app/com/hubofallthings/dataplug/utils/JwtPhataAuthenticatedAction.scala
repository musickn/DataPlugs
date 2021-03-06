/*
 * Copyright (C) 2016-2019 Dataswift Ltd - All Rights Reserved
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Written by Andrius Aucinas <andrius.aucinas@dataswift.io>, 10 2016
 */

package com.hubofallthings.dataplug.utils

import com.hubofallthings.dataplug.models.User
import com.hubofallthings.dataplug.services.UserService
import javax.inject.Inject
import com.nimbusds.jwt.SignedJWT
import org.joda.time.DateTime
import play.api.{ Configuration, Logger }
import play.api.libs.json.Json
import play.api.mvc._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class JwtPhataAuthenticatedRequest[A](val identity: User, val request: Request[A])
  extends WrappedRequest[A](request)

class JwtPhataAuthenticatedAction @Inject() (
    identityVerification: JwtIdentityVerification,
    configuration: Configuration,
    userService: UserService,
    bodyParsers: PlayBodyParsers)(
    implicit
    val executionContext: ExecutionContext) extends ActionBuilder[JwtPhataAuthenticatedRequest, AnyContent] {

  private val tokenExpirationInterval = 3 // days
  private val logger = Logger(this.getClass)
  val parser = bodyParsers.default

  def invokeBlock[A](request: Request[A], block: (JwtPhataAuthenticatedRequest[A]) => Future[Result]): Future[Result] = {
    request.headers.get("X-Auth-Token")
      .map(validateJwtToken)
      .map { eventualMaybeUser =>
        eventualMaybeUser.flatMap { maybeIdentity =>
          maybeIdentity map { identity =>
            userService.save(identity)
              .flatMap(_ => userService.retrieve(identity.loginInfo).map(_.get)) // must have a user when we've just inserted one
              .flatMap(saved => block(new JwtPhataAuthenticatedRequest(saved, request)))
          } getOrElse {
            Future.successful(Results.Unauthorized)
          }

        } recover {
          case e =>
            logger.error(s"Error while authenticating: ${e.getMessage}")
            Results.Unauthorized(Json.obj("status" -> "unauthorized", "message" -> s"No valid login information"))
        }
      }
      .getOrElse(Future.successful(Results.Unauthorized))
  }

  def validateJwtToken(token: String): Future[Option[User]] = {
    logger.debug(s"Starting JWT token validation, token --- $token")

    val expectedApplication = configuration.get[String]("service.name")
    val maybeSignedJWT = Try(SignedJWT.parse(token))

    maybeSignedJWT.map { signedJWT =>
      val claimSet = signedJWT.getJWTClaimsSet
      val fresh = claimSet.getExpirationTime.after(DateTime.now().toDate) &&
        claimSet.getIssueTime.after(DateTime.now().minusDays(tokenExpirationInterval).toDate)
      val applicationMatches = Option(claimSet.getClaim("application")).contains(expectedApplication)

      if (fresh && applicationMatches) {
        logger.debug(s"JWT token validation succeeded: issuer --- ${claimSet.getIssuer}")
        val identity = User("hatlogin", claimSet.getIssuer, List())
        identityVerification.verifiedIdentity(identity, signedJWT)
      }
      else {
        logger.warn(s"JWT token validation failed: fresh - $fresh, application - $applicationMatches")
        Future(None)
      }
    } getOrElse {
      // JWT parse error
      Future(None)
    }
  }
}

class JwtPhataAwareRequest[A](val maybeUser: Option[User], val request: Request[A])
  extends WrappedRequest[A](request)

class JwtPhataAwareAction @Inject() (
    identityVerification: JwtIdentityVerification,
    configuration: play.api.Configuration,
    userService: UserService,
    jwtAuthenticatedAction: JwtPhataAuthenticatedAction)(
    implicit
    val executionContext: ExecutionContext) extends ActionBuilder[JwtPhataAwareRequest, AnyContent] {

  val logger = Logger(this.getClass)
  val parser = jwtAuthenticatedAction.parser

  def invokeBlock[A](request: Request[A], block: (JwtPhataAwareRequest[A]) => Future[Result]): Future[Result] = {
    request.headers.get("X-Auth-Token")
      .orElse(request.getQueryString("token"))
      .map(jwtAuthenticatedAction.validateJwtToken)
      .map { eventualMaybeUser =>
        eventualMaybeUser.flatMap { maybe =>
          logger.debug(s"User auth checked, got back user $maybe")
          val eventuallySavedUser = maybe map { identity =>
            userService.save(identity)
              .flatMap(_ => userService.retrieve(identity.loginInfo)) // must have a user when we've just inserted one
          } getOrElse {
            Future.successful(None)
          }
          eventuallySavedUser flatMap { maybeUser =>
            block(new JwtPhataAwareRequest(maybeUser, request))
          }
        } recoverWith {
          case e =>
            logger.error(s"Error while authenticating: ${e.getMessage}")
            block(new JwtPhataAwareRequest(None, request))
        }
      }
      .getOrElse(block(new JwtPhataAwareRequest(None, request)))
  }
}
