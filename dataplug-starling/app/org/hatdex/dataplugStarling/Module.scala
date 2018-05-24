/*
 * Copyright (C) HAT Data Exchange Ltd - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Andrius Aucinas <andrius.aucinas@hatdex.org>, 10 2016
 */

package org.hatdex.dataplugStarling

import akka.actor.{ActorSystem, Scheduler}
import com.google.inject.{AbstractModule, Provides}
import com.mohiva.play.silhouette.api.Provider
import com.mohiva.play.silhouette.api.util.HTTPLayer
import com.mohiva.play.silhouette.impl.providers._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import net.codingwell.scalaguice.ScalaModule
import org.hatdex.dataplug.actors.DataPlugManagerActor
import org.hatdex.dataplug.apiInterfaces.{DataPlugOptionsCollector, DataPlugOptionsCollectorRegistry, DataPlugRegistry}
import org.hatdex.dataplug.controllers.{DataPlugViewSet, DataPlugViewSetDefault}
import org.hatdex.dataplug.dao.{DataPlugEndpointDAO, DataPlugEndpointDAOImpl}
import org.hatdex.dataplug.services._
import org.hatdex.libs.dal.SchemaMigration
import org.hatdex.dataplug.dal.SchemaMigrationImpl
import org.hatdex.dataplugStarling.apiInterfaces._
import org.hatdex.dataplugStarling.apiInterfaces.authProviders.StarlingProvider
import org.hatdex.dataplugStarling.apiInterfaces.authProviders.SpotifyProvider
import org.hatdex.dataplugStarling.apiInterfaces.{StarlingProfileCheck, StarlingProfileInterface, StarlingTransactionsInterface}
import play.api.Configuration
import play.api.libs.concurrent.AkkaGuiceSupport

/**
 * The Guice module which wires all Silhouette dependencies.
 */
class Module extends AbstractModule with ScalaModule with AkkaGuiceSupport {

  /**
   * Configures the module.
   */
  def configure() {
    // Automatic database schema migrations
    bind[SchemaMigration].to[SchemaMigrationImpl]
    bind[StartupService].to[StartupServiceImpl].asEagerSingleton()

    bind[DataPlugEndpointDAO].to[DataPlugEndpointDAOImpl]
    bind[DataPlugEndpointService].to[DataPlugEndpointServiceImpl]

    bind[DataPlugViewSet].to[DataPlugViewSetDefault]

    //    bindActorFactory[InjectedHatClientActor, InjectedHatClientActor.Factory]
    bindActor[DataPlugManagerActor]("dataplug-manager")
  }

  /**
   * Provides the social provider registry.
   *
   * @param googleCalendarEndpoint The google calendar api endpoint implementation, injected
   * @return The DataPlugRegistry.
   */
  @Provides
  def provideDataPlugCollection(
                                 spotifyProfileInterface: StarlingProfileInterface,
                                 spotifyRecentlyPlayedInterface: StarlingTransactionsInterface): DataPlugRegistry = {

    DataPlugRegistry(Seq(spotifyProfileInterface, spotifyRecentlyPlayedInterface))
  }

  @Provides
  def provideDataPlugEndpointChoiceCollection(
    spotifyProvider: SpotifyProvider,
    spotifyProfileCheck: StarlingProfileCheck): DataPlugOptionsCollectorRegistry = {

    val variants: Seq[(Provider, DataPlugOptionsCollector)] = Seq((spotifyProvider, spotifyProfileCheck))
    DataPlugOptionsCollectorRegistry(variants)
  }

  /**
   * Provides the social provider registry.
   *
   * @param spotifyProvider The Fitbit provider implementation.
   * @return The Silhouette environment.
   */
  @Provides
  def provideSocialProviderRegistry(
    spotifyProvider: SpotifyProvider): SocialProviderRegistry = {

    SocialProviderRegistry(Seq(
      spotifyProvider))
  }

  /**
   * Provides the Fitbit provider.
   *
   * @param httpLayer The HTTP layer implementation.
   * @param stateHandler The OAuth2 state provider implementation.
   * @param configuration The Play configuration.
   * @return The Fitbit provider.
   */
  @Provides
  def provideSpotifyProvider(
    httpLayer: HTTPLayer,
    stateHandler: SocialStateHandler,
    configuration: Configuration): SpotifyProvider = {
    new SpotifyProvider(httpLayer, stateHandler, configuration.underlying.as[OAuth2Settings]("silhouette.spotify"))
  }

  @Provides
  def providesAkkaActorScheduler(actorSystem: ActorSystem): Scheduler = {
    actorSystem.scheduler
  }

}