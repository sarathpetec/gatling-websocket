package com.giltgroupe.util.gatling.websocket

import com.excilys.ebi.gatling.core.action.builder.ActionBuilder
import com.excilys.ebi.gatling.core.action.{BaseActor, Bypass, Action, system}
import com.excilys.ebi.gatling.core.config.ProtocolConfigurationRegistry
import com.excilys.ebi.gatling.core.result.message.RequestStatus._
import com.excilys.ebi.gatling.core.result.writer.DataWriter
import com.excilys.ebi.gatling.core.session.{Session, EvaluatableString}
import com.excilys.ebi.gatling.core.util.StringHelper._
import akka.actor.{Props, ActorRef}
import com.typesafe.config.ConfigFactory
import grizzled.slf4j.Logging
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.websocket.{WebSocket, WebSocketClientFactory, RandomMaskGen}
import java.net.URI
import java.io.IOException

object Predef {
  /**
   * References a web socket that can have actions performed on it.
   *
   * @param attributeName The name of the session attribute that stores the socket
   */
  def websocket(attributeName: String) = new WebSocketBaseBuilder(attributeName)

  /** The default Jetty WebSocket client. */
  implicit object WebSocketClient extends WebSocketClient with Logging {
    private[this] val client = {
      val pool = new QueuedThreadPool(WebSocketConfiguration.maxClientThreads)
      val factory = new WebSocketClientFactory(pool, new RandomMaskGen(), WebSocketConfiguration.bufferSize)
      factory.start()

      system.registerOnTermination(factory.stop())

      val client = factory.newWebSocketClient()
      client.setMaxIdleTime(WebSocketConfiguration.maxIdleTimeInMs)

      client
    }

    def open(uri: URI, websocket: WebSocket) {
      client.open(uri, websocket)
    }
  }

  /** The default Gatling request logger. */
  implicit object RequestLogger extends RequestLogger {
    def logRequest(session: Session, actionName: String, requestStatus: RequestStatus, started: Long, ended: Long, errorMessage: Option[String]) {
      DataWriter.logRequest(session.scenarioName, session.userId, actionName,
        started, ended, ended, ended,
        requestStatus, errorMessage)
    }
  }
}

trait WebSocketClient {
  @throws(classOf[IOException])
  def open(uri: URI, websocket: WebSocket)
}

trait RequestLogger {
  def logRequest(session: Session, actionName: String, requestStatus: RequestStatus, started: Long, ended: Long, errorMessage: Option[String] = None)
}

class WebSocketBaseBuilder(val attributeName: String) {
  /**
   * Opens a web socket and stores it in the session.
   *
   * @param fUrl The socket URL
   * @param actionName The action name in the log
   */
  def open(fUrl: EvaluatableString, actionName: String = attributeName)(implicit webSocketClient: WebSocketClient, requestLogger: RequestLogger) = new OpenWebSocketActionBuilder(attributeName, actionName, fUrl, webSocketClient, requestLogger)

  /**
   * Sends a message on the given socket.
   *
   * @param fMessage The message
   * @param actionName The action name in the log
   */
  def sendMessage(fMessage: EvaluatableString, actionName: String = attributeName) = new SendWebSocketMessageActionBuilder(attributeName, actionName, fMessage)

  /**
   * Closes a web socket.
   *
   * @param actionName The action name in the log
   */
  def close(actionName: String = attributeName) = new CloseWebSocketActionBuilder(attributeName, actionName)
}

class OpenWebSocketActionBuilder(val attributeName: String, val actionName: String, val fUrl: EvaluatableString, val webSocketClient: WebSocketClient, val requestLogger: RequestLogger, val next: ActorRef = null) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder = new OpenWebSocketActionBuilder(attributeName, actionName, fUrl, webSocketClient, requestLogger, next)

  def build(registry: ProtocolConfigurationRegistry): ActorRef = system.actorOf(Props(new OpenWebSocketAction(attributeName, actionName, fUrl, webSocketClient, requestLogger, next, registry)))
}

class SendWebSocketMessageActionBuilder(val attributeName: String, val actionName: String, val fMessage: EvaluatableString, val next: ActorRef = null) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder = new SendWebSocketMessageActionBuilder(attributeName, actionName, fMessage, next)

  def build(registry: ProtocolConfigurationRegistry): ActorRef = system.actorOf(Props(new SendWebSocketMessageAction(attributeName, actionName, fMessage, next, registry)))
}

class CloseWebSocketActionBuilder(val attributeName: String, val actionName: String, val next: ActorRef = null) extends ActionBuilder {
  def withNext(next: ActorRef): ActionBuilder = new CloseWebSocketActionBuilder(attributeName, actionName, next)

  def build(registry: ProtocolConfigurationRegistry): ActorRef = system.actorOf(Props(new CloseWebSocketAction(attributeName, actionName, next, registry)))
}

private[websocket] class OpenWebSocketAction(attributeName: String, actionName: String, fUrl: EvaluatableString, webSocketClient: WebSocketClient, requestLogger: RequestLogger, next: ActorRef, registry: ProtocolConfigurationRegistry) extends Action(attributeName, next) with Bypass {
  def execute(session: Session) {
    info("Opening websocket '" + attributeName + "': Scenario '" + session.scenarioName + "', UserId #" + session.userId)

    val actor = context.actorOf(Props(new WebSocketActor(attributeName, requestLogger)))

    val started = System.currentTimeMillis
    try {
      webSocketClient.open(URI.create(fUrl(session)), new WebSocket.OnTextMessage {
        var opened = false

        def onOpen(connection: WebSocket.Connection) {
          opened = true
          actor ! OnOpen(actionName, connection, started, System.currentTimeMillis, next, session)
        }

        def onMessage(data: String) {
          actor ! OnMessage(data)
        }

        def onClose(closeCode: Int, message: String) {
          if (opened) {
            actor ! OnClose(closeCode, message)
          }
          else {
            actor ! OnFailedOpen(actionName, "CloseCode " + closeCode + ", Message '" + message + "'", started, System.currentTimeMillis, next, session)
          }
        }
      })
    }
    catch {
      case e: IOException =>
        actor ! OnFailedOpen(actionName, e.getMessage, started, System.currentTimeMillis, next, session)
    }
  }
}

private[websocket] class SendWebSocketMessageAction(attributeName: String, actionName: String, fMessage: EvaluatableString, next: ActorRef, registry: ProtocolConfigurationRegistry) extends Action(attributeName, next) with Bypass {
  def execute(session: Session) {
    session.getAttributeAsOption[(ActorRef, _)](attributeName).foreach(_._1 ! SendMessage(actionName, fMessage(session), next, session))
  }
}

private[websocket] class CloseWebSocketAction(attributeName: String, actionName: String, next: ActorRef, registry: ProtocolConfigurationRegistry) extends Action(attributeName, next) with Bypass {
  def execute(session: Session) {
    info("Closing websocket '" + attributeName + "': Scenario '" + session.scenarioName + "', UserId #" + session.userId)
    session.getAttributeAsOption[(ActorRef, _)](attributeName).foreach(_._1 ! Close(actionName, next, session))
  }
}

private[websocket] class WebSocketActor(val attributeName: String, requestLogger: RequestLogger) extends BaseActor {
  var connection: Option[WebSocket.Connection] = None
  var unexpectedCloseMessage: Option[String] = None

  def receive = {
    case OnOpen(actionName, conn, started, ended, next, session) =>
      requestLogger.logRequest(session, actionName, OK, started, ended)
      connection = Some(conn)
      next ! session.setAttribute(attributeName, (self, connection))

    case OnFailedOpen(actionName, message, started, ended, next, session) =>
      warn("Websocket '" + attributeName + "' failed to open: " + message)
      requestLogger.logRequest(session, actionName, KO, started, ended, Some(message))
      next ! session.setFailed
      context.stop(self)

    case OnMessage(data) =>
      if (isDebugEnabled) debug("Received message on websocket '" + attributeName + "':" + END_OF_LINE + data)

    case OnClose(closeCode, message) =>
      unexpectedCloseMessage = Some("Websocket '" + attributeName + "' was unexpectedly closed: CloseCode " + closeCode + ", Message '" + message + "'")
      warn(unexpectedCloseMessage.get)

    case SendMessage(actionName, message, next, session) =>
      if (!handleUnexpectedClose(actionName, next, session)) {
        val started = System.currentTimeMillis
        try {
          connection.foreach(_.sendMessage(message))
          requestLogger.logRequest(session, actionName, OK, started, System.currentTimeMillis)
          next ! session
        }
        catch {
          case e: IOException =>
            warn("Error sending message on websocket '" + attributeName + "'", e)
            requestLogger.logRequest(session, actionName, KO, started, System.currentTimeMillis, Some(e.getMessage))
            next ! session.setFailed
            context.stop(self)
        }
      }

    case Close(actionName, next, session) =>
      if (!handleUnexpectedClose(actionName, next, session)) {
        val started = System.currentTimeMillis
        connection.foreach(_.close)
        requestLogger.logRequest(session, actionName, OK, started, System.currentTimeMillis)
        next ! session
        context.stop(self)
      }
  }

  def handleUnexpectedClose(actionName: String, next: ActorRef, session: Session) = {
    if (unexpectedCloseMessage.isDefined) {
      val now = System.currentTimeMillis
      requestLogger.logRequest(session, actionName, KO, now, now, unexpectedCloseMessage)
      unexpectedCloseMessage = None
      next ! session.setFailed
      context.stop(self)
      true
    }
    else {
      false
    }
  }
}

private[websocket] case class OnOpen(actionName: String, connection: WebSocket.Connection, started: Long, ended: Long, next: ActorRef, session: Session)
private[websocket] case class OnFailedOpen(actionName: String, message: String, started: Long, ended: Long, next: ActorRef, session: Session)
private[websocket] case class OnMessage(data: String)
private[websocket] case class OnClose(closeCode: Int, message: String)

private[websocket] case class SendMessage(actionName: String, message: String, next: ActorRef, session: Session)
private[websocket] case class Close(actionName: String, next: ActorRef, session: Session)

private[websocket] object WebSocketConfiguration {
  val config = ConfigFactory.parseResources(getClass.getClassLoader, "gatling.conf")

  val maxClientThreads = config.getInt("gatling.websocket.maxClientThreads")
  val bufferSize = config.getInt("gatling.websocket.bufferSize")
  val maxIdleTimeInMs = config.getInt("gatling.websocket.maxIdleTimeInMs")
}
