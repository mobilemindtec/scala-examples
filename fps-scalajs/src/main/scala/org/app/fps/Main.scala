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
import org.scalajs.dom
import org.scalajs.dom.{HTMLButtonElement, MouseEvent, window}

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.util.Try

case class User(id: Int,
                login: String,
                url: String,
                avatar_url: String
               ) derives NativeConverter



// usado para conectar eventos de subscriptions
private val dynOwner = new DynamicOwner(() => ())

// connecta o evento de validação do code no eventBuss
private val dynSub = DynamicSubscription.unsafe(
  dynOwner,
  activate = (owner: Owner) =>
    given o: Owner = owner
    appStart()
)

val githubUrl = "https://api.github.com/users"

def appStart2(using owner: Owner) =
  val refreshButton = document.querySelector(".refresh").asInstanceOf[HTMLButtonElement]
  val _refreshClickStream = DomEventStream[dom.MouseEvent](refreshButton, "click")
  val refreshClickSignal = _refreshClickStream.startWithNone

  val closeButton = document.querySelector(".close1").asInstanceOf[HTMLButtonElement]
  val _closeClickStream = DomEventStream[dom.MouseEvent](closeButton, "click")
  val closeClickSignal = _closeClickStream.startWithNone

  val refreshClickStream = refreshClickSignal.changes
  val closeClickStream = closeClickSignal.changes

  refreshClickStream.addObserver(Observer{ _ => println("refresh clicked") })
  closeClickStream.addObserver(Observer{ _ => println("close button clicked") })

  val combinedStream = refreshClickSignal.combineWith(closeClickSignal).map {
    (x, y) => s"combined!! $x, $y"
  }

  val obs = combinedStream.addObserver(Observer{
    s => println(s)
  })


  obs

def appStart()(implicit owner: Owner) =

  val refreshButton = document.querySelector(".refresh").asInstanceOf[HTMLButtonElement]
  val refreshClickStream = EventStream.merge(
    DomEventStream[dom.MouseEvent](refreshButton, "click").mapToUnit,
    EventStream.fromValue(())
  )

  val closeButton = document.querySelector(".close1").asInstanceOf[HTMLButtonElement]
  val closeClickStream = EventStream.merge(
    DomEventStream[dom.MouseEvent](closeButton, "click").mapToUnit,
    EventStream.fromValue(())
  )

  val requestStream = refreshClickStream
    .map {
      _ =>
        val randomOffset = Math.floor(Math.random()*500)
        s"$githubUrl?since=$randomOffset"
    }

  val responseStream = requestStream
    .flatMap {
      requestUrl =>
        FetchStream
          .get(requestUrl)
          .map(s => JSON.parse(s))
          .map(r => NativeConverter[List[User]].fromNative(r))
          .recover {
            case e => Some(List())
          }
          .debugLogErrors()

    }

  def next(users: List[User]) = users(Math.floor(Math.random() * users.length).toInt)

  val suggestionStream = responseStream
    .combineWith(closeClickStream)
    .drop(1)
    .map(next)

  suggestionStream.addObserver(Observer{
    suggestion => println(s"suggestion: $suggestion")
  })


@main def main: Unit =
  dynOwner.activate()
  println("scalajs rocks =)")

