package org.hatdex.dataplugFacebook.models

import play.api.libs.json.{ Json, Reads }

case class FacebookEvent(
    id: String,
    name: String,
    description: Option[String],
    owner: Option[FacebookEventOwner],
    start_time: String,
    end_time: Option[String],
    updated_time: Option[String],
    attending_count: Option[Int],
    declined_count: Option[Int],
    maybe_count: Option[Int],
    noreply_count: Option[Int],
    rsvp_status: String,
    `type`: Option[String],
    place: Option[FacebookPlace])

case class FacebookEventOwner(
    id: String,
    name: String)

case class FacebookPlace(
    id: Option[String],
    name: String,
    location: Option[FacebookLocation])

case class FacebookLocation(
    city: Option[String],
    country: Option[String],
    latitude: Double,
    longitude: Double,
    located_in: Option[String],
    name: Option[String],
    state: Option[String],
    street: Option[String],
    zip: Option[String])

object FacebookPlace {
  implicit val facebookLocationReads: Reads[FacebookLocation] = Json.reads[FacebookLocation]
  implicit val facebookPlaceReads: Reads[FacebookPlace] = Json.reads[FacebookPlace]
}

object FacebookEvent {
  implicit val facebookEventOwnerReads: Reads[FacebookEventOwner] = Json.reads[FacebookEventOwner]

  implicit val facebookEventReads: Reads[FacebookEvent] = Json.reads[FacebookEvent]
}
