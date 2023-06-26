package org.app.fps

import org.scalajs.dom.window.document
import com.raquo.airstream.*
import com.raquo.airstream.core.{EventStream, Observable, Observer, Signal}
import com.raquo.airstream.eventbus.{EventBus, EventBusStream, WriteBus}
import com.raquo.airstream.flatten.FlattenStrategy
import com.raquo.airstream.ownership.{DynamicOwner, DynamicSubscription, Owner, Subscription}
import com.raquo.airstream.web.{DomEventStream, FetchStream}
import com.raquo.laminar.api.L.{SwitchSignalStrategy, Val}
import org.getshaka.nativeconverter.NativeConverter
import org.scalajs
import org.scalajs.dom
import org.scalajs.dom.{HTMLButtonElement, HTMLImageElement, HTMLLinkElement, HTMLUListElement, MouseEvent, window}

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.util.Try

case class User(id: Int,
                login: String,
                url: String,
                avatar_url: String
               ) derives NativeConverter



private val dynOwner = new DynamicOwner(() => ())

private val dynSub = DynamicSubscription.unsafe(
  dynOwner,
  activate = (owner: Owner) =>
    given o: Owner = owner
    appStart()
)

def createButtonClickStream(selector: String) =
  val btn = document.querySelector(selector).asInstanceOf[HTMLLinkElement]
  EventStream.merge(
    EventStream.fromValue(()),
    DomEventStream[MouseEvent](btn, "click").mapToUnit,
  )
def renderSuggestion(user: Option[User], selector: String): Unit =
  val el = document.querySelector(selector).asInstanceOf[HTMLUListElement]

  if user.isEmpty then
    el.style.visibility = "hidden"
  else
    val u = user.get
    el.style.visibility = "visible"
    val usernameEl = el.querySelector(".username").asInstanceOf[HTMLLinkElement]
    val imgEl = el.querySelector("img").asInstanceOf[HTMLImageElement]
    usernameEl.href = u.url
    usernameEl.textContent = u.login
    imgEl.src = ""
    imgEl.src = u.avatar_url

def appStart()(using owner: Owner) =

  val githubUrl = "https://api.github.com/users"

  val refreshClickStream = createButtonClickStream(".refresh")
  val close1ClickStream = createButtonClickStream(".close1")
  val close2ClickStream = createButtonClickStream(".close2")
  val close3ClickStream = createButtonClickStream(".close3")

  def randomOffset = Math.floor(Math.random()*500)

  def fetchStream(requestUrl: String) = FetchStream
    .get(requestUrl)
    .map(s => JSON.parse(s))
    .map(r => NativeConverter[List[User]].fromNative(r))

  def nextUser(users: List[User]) =
    if users.isEmpty then None
    else
      val randomIdx = Math.floor(Math.random() * users.length).toInt
      Some(users(randomIdx))

  val requestStream = refreshClickStream
    .map(_ => s"$githubUrl?since=$randomOffset")

  val responseStream = requestStream
    .flatMap(fetchStream)
    .drop(1)

  val clearSuggestionsStream = EventStream.merge(
    EventStream.fromValue(List()),
    refreshClickStream.map(_ => List())
  )

  def createSuggestionStream(closeClickStream: EventStream[Unit]) =
    EventStream.merge(
      closeClickStream
        .combineWith(responseStream),
      clearSuggestionsStream
    ).map(nextUser)


  val suggestion1Stream = createSuggestionStream(close1ClickStream)
  val suggestion2Stream = createSuggestionStream(close2ClickStream)
  val suggestion3Stream = createSuggestionStream(close3ClickStream)

  suggestion1Stream.addObserver(Observer{
    user => renderSuggestion(user, ".suggestion1")
  })

  suggestion2Stream.addObserver(Observer {
    user => renderSuggestion(user, ".suggestion2")
  })

  suggestion3Stream.addObserver(Observer {
    user => renderSuggestion(user, ".suggestion3")
  })

@main def main: Unit =
  dynOwner.activate()

