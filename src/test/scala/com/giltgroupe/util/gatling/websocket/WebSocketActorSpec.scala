package com.giltgroupe.util.gatling.websocket

import akka.actor.{Actor, Props}
import akka.testkit.TestActorRef
import com.excilys.ebi.gatling.core.Predef._
import com.excilys.ebi.gatling.core.action.system
import com.excilys.ebi.gatling.core.config.ProtocolConfigurationRegistry
import com.excilys.ebi.gatling.core.result.message.RequestStatus._
import com.excilys.ebi.gatling.core.session.Session
import org.eclipse.jetty.websocket.WebSocket
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Matchers.{eq => isEq}
import org.mockito.Mockito.reset
import org.specs2.mock._
import mockito.MocksCreation
import org.slf4j.LoggerFactory
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.{AllExpectations, Scope}
import java.net.URI
import java.io.IOException

@RunWith(classOf[JUnitRunner])
class WebSocketActorSpec extends Specification with AllExpectations with Mockito {
  step {
    // initialize logging to avoid substitute logger error messages
    LoggerFactory.getLogger(classOf[WebSocketActorSpec])
    success
  }

  "A WebSocketActor" should {
    "record a successful open and advance" in new scope {
      open(webSocketClient(_.onOpen(mock[WebSocket.Connection].smart)))

      there was one(requestLogger).logRequest(any[Session], anyString, isEq(OK), anyLong, anyLong, isEq(null))
      next.underlyingActor.session.map(_.isAttributeDefined("testAttributeName")) mustEqual Some(true)
      next.underlyingActor.session.map(_.isFailed) mustEqual Some(false)
    }

    "record a failed open and advance" in new scope {
      open(mock[WebSocketClient].open(any[URI], any[WebSocket]) throws new IOException("testErrorMessage"))

      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, anArgThat(contains("testErrorMessage")))
      next.underlyingActor.session.map(_.isAttributeDefined("testAttributeName")) mustEqual Some(false)
      next.underlyingActor.session.map(_.isFailed) mustEqual Some(true)
    }

    "record an incomplete open as failed and advance" in new scope {
      open(webSocketClient(_.onClose(1000, "testCloseMessage")))

      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, anArgThat(contains("testCloseMessage")))
      next.underlyingActor.session.map(_.isAttributeDefined("testAttributeName")) mustEqual Some(false)
      next.underlyingActor.session.map(_.isFailed) mustEqual Some(true)
    }

    "record a successful send and advance" in new scope {
      val connection = mock[WebSocket.Connection].smart
      openSuccessfully(connection)
      sendMessage("testMessage")

      there was one(connection).sendMessage(isEq("testMessage"))
      there was one(requestLogger).logRequest(any[Session], anyString, isEq(OK), anyLong, anyLong, isEq(null))
      next.underlyingActor.session.map(_.isFailed) mustEqual Some(false)
    }

    "record a failed send and advance" in new scope {
      openSuccessfully(mock[WebSocket.Connection].sendMessage(anyString) throws new IOException("testErrorMessage"))
      sendMessage("testMessage")

      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, anArgThat(contains("testErrorMessage")))
      next.underlyingActor.session.map(_.isFailed) mustEqual Some(true)
    }

    "record a send after an unexpected close as failed and advance" in new scope {
      val connection = mock[WebSocket.Connection].smart
      closeUnexpectedly(connection)
      sendMessage("testMessage")

      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, anArgThat(contains("testCloseMessage")))
      there was no(connection).sendMessage(anyString)
      next.underlyingActor.session.map(_.isFailed) mustEqual Some(true)
    }

    "record a successful close and advance" in new scope {
      val connection = mock[WebSocket.Connection].smart
      openSuccessfully(connection)
      close()

      there was one(connection).close()
      there was one(requestLogger).logRequest(any[Session], anyString, isEq(OK), anyLong, anyLong, isEq(null))
      next.underlyingActor.session.map(_.isFailed) mustEqual Some(false)
    }

    "record a close after an unexpected close as failed and advance" in new scope {
      val connection = mock[WebSocket.Connection].smart
      closeUnexpectedly(connection)
      close()

      there was one(requestLogger).logRequest(any[Session], anyString, isEq(KO), anyLong, anyLong, anArgThat(contains("testCloseMessage")))
      there was no(connection).sendMessage(anyString)
      next.underlyingActor.session.map(_.isFailed) mustEqual Some(true)
    }
  }

  private trait scope extends Scope with MocksCreation {
    val requestLogger = mock[RequestLogger]
    var next: TestActorRef[DummyAction] = _

    def webSocketClient(open: (WebSocket) => Unit) = {
      mock[WebSocketClient].open(any[URI], any[WebSocket]) answers {(params, _) =>
        open(params.asInstanceOf[Array[_]](1).asInstanceOf[WebSocket])
      }
    }

    def open(webSocketClient: WebSocketClient) {
      next = TestActorRef[DummyAction](Props(new DummyAction))(system)
      val action = Predef.websocket("testAttributeName")
        .open("ws://dummy/", "testRequestName")(webSocketClient, requestLogger)
        .withNext(next).asInstanceOf[OpenWebSocketActionBuilder]
        .build(DummyProtocolConfigurationRegistry)

      action ! new Session("test", 0)
    }

    def openSuccessfully(connection: WebSocket.Connection) {
      open(webSocketClient(_.onOpen(connection)))
      reset(requestLogger)
    }

    def closeUnexpectedly(connection: WebSocket.Connection) {
      open(webSocketClient{s => s.onOpen(connection); s.onClose(1000, "testCloseMessage")})
      reset(requestLogger)
    }

    def sendMessage(message: String) {
      val session = next.underlyingActor.session.get
      next = TestActorRef[DummyAction](Props(new DummyAction))(system)
      val action = Predef.websocket("testAttributeName")
        .sendMessage(message)
        .withNext(next).asInstanceOf[SendWebSocketMessageActionBuilder]
        .build(DummyProtocolConfigurationRegistry)

      action ! session
    }

    def close() {
      val session = next.underlyingActor.session.get
      next = TestActorRef[DummyAction](Props(new DummyAction))(system)
      val action = Predef.websocket("testAttributeName")
        .close()
        .withNext(next).asInstanceOf[CloseWebSocketActionBuilder]
        .build(DummyProtocolConfigurationRegistry)

      action ! session
    }
  }

  private case class contains(str: String) extends ArgumentMatcher[Option[String]] {
    def matches(argument: Any) = argument.asInstanceOf[Option[String]].map(_.contains(str)).getOrElse(false)
  }
}

class DummyAction extends Actor {
  var session = Option.empty[Session]

  def receive = {
    case s: Session => session = Some(s)
  }
}

object DummyProtocolConfigurationRegistry extends ProtocolConfigurationRegistry(Map())