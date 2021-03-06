package xmpp

import entities.CcsMessage
import org.jivesoftware.smack.*
import org.jivesoftware.smack.ConnectionConfiguration.SecurityMode
import org.jivesoftware.smack.filter.PacketTypeFilter
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Packet
import org.jivesoftware.smack.packet.PacketExtension
import org.jivesoftware.smack.provider.PacketExtensionProvider
import org.jivesoftware.smack.provider.ProviderManager
import org.json.simple.JSONValue
import org.json.simple.parser.ParseException
import org.xmlpull.v1.XmlPullParser
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger
import javax.net.ssl.SSLSocketFactory

/**
 * Sample Smack implementation of a client for GCM Cloud Connection Server.
 * Most of it has been taken more or less verbatim from Googles
 * documentation: http://developer.android.com/google/gcm/ccs.html
 * <br>
 * But some additions have been made. Bigger changes are annotated like that:
 * "/// new".
 * <br>
 * Those changes have to do with parsing certain type of messages
 * as well as with sending messages to a list of recipients. The original code
 * only covers sending one message to exactly one recipient.
 */
public class CcsClient {

    constructor(projectId: String, apiKey: String, debuggable: Boolean) {

        this.projectId = projectId
        this.apiKey = apiKey
        this.debuggable = debuggable

        // Add GcmPacketExtension
        ProviderManager.getInstance().addExtensionProvider(Config.GCM_ELEMENT_NAME, Config.GCM_NAMESPACE, object: PacketExtensionProvider {
            override fun parseExtension(parser: XmlPullParser): PacketExtension {
                val json = parser.nextText()
                return GcmPacketExtension(json)
            }
        })
    }

    val projectId: String
    val apiKey: String
    val debuggable: Boolean
    val logger = Logger.getLogger(this.javaClass.name)
    val random = Random()

    var connection: XMPPConnection? = null
    var config: ConnectionConfiguration? = null

    companion object {

        private var singleton: CcsClient? = null

        @Synchronized fun create(projectId: String, apiKey: String, debuggable: Boolean): CcsClient {
            if(singleton == null) {
                singleton = CcsClient(projectId, apiKey, debuggable)
            }
            return singleton!!
        }

        fun create(): CcsClient {
            return singleton!!
        }

        @JvmStatic fun main(args: Array<String>) {
            val ccsClient = CcsClient.Companion.create(Config.PROJECT_ID, Config.SERVER_PASS, true)
            try {
                ccsClient.connect()
            } catch (e: XMPPException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Returns a random message id to uniquely identify a message.
     *
     * <p>
     * Note: This is generated by a pseudo random number generator for
     * illustration purpose, and is not guaranteed to be unique.
     *
     */
    fun getRandomMessageId(): String {
        return "m-" + java.lang.Long.toString(random.nextLong())
    }

    /**
     * Sends a downstream GCM message.
     */
    fun send(jsonRequest: String) {
        val request = GcmPacketExtension(jsonRequest).toPacket()
        connection?.sendPacket(request)
    }

    /**
     * Sends a message to multiple recipients. Kind of like the old
     * HTTP message with the list of regIds in the "registration_ids" field.
     */
    fun sendBroadcast(payload: HashMap<String, String>, collapseKey: String,
            timeToLive: Long, delayWhileIdle: Boolean, recipients: List<String>) {
        val map = createAttributeMap(null, null, payload, collapseKey, timeToLive, delayWhileIdle)
        recipients.forEach { toRegId ->
            val messageId = getRandomMessageId()
            map.put("message_id", messageId)
            map.put("to", toRegId)
            val jsonRequest = createJsonMessage(map)
            send(jsonRequest)
        }
    }

    /**
     * Handles an upstream data message from a device application.
     */
    fun handleIncomingDataMessage(msg: CcsMessage) {
        if (msg.mPayload.get("ACTION") != null) {
            val processor = ProcessorFactory.getProcessor(msg.mPayload.get("ACTION"))
            processor.handleMessage(msg)
        }
    }

    /**
     *
     */
    fun getMessage(jsonObject: Map<String, Any>): CcsMessage {
        val from = jsonObject.get("from").toString()

        // PackageName of the application that sent this message.
        val category = jsonObject.get("category").toString()

        // unique id of this message
        val messageId = jsonObject.get("message_id").toString()

        val map = jsonObject.get("data")

        val payload: MutableMap<String, String> = when(map) {
            is MutableMap<*,*> -> map as MutableMap<String, String>
            else -> hashMapOf()
        }

        val msg = CcsMessage(from, category, messageId, payload)

        return msg
    }

    /**
     * Handles an ACK.
     *
     * <p>
     * By default, it only logs a INFO message, but subclasses could override it
     * to properly handle ACKS.
     */
    fun handleAckReceipt(jsonObject: Map<String, Any>) {
        val messageId = jsonObject.get("message_id").toString()
        val from = jsonObject.get("from").toString()
        logger.log(Level.INFO, "handleAckReceipt() from: $from , messageId: $messageId")
    }

    /**
     * Handles a NACK.
     *
     * <p>
     * By default, it only logs a INFO message, but subclasses could override it
     * to properly handle NACKS.
     */
    fun handleNackReceipt(jsonObject: Map<String, Any>) {
        val messageId = jsonObject.get("message_id").toString()
        val from = jsonObject.get("from").toString()
        logger.log(Level.INFO, "handleNackReceipt() from: $from, messageId: $messageId")
    }

    /**
     * Creates a JSON encoded GCM message.
     *
     * @param to RegistrationId of the target device (Required).
     * @param messageId Unique messageId for which CCS will send an "ack/nack"
     * (Required).
     * @param payload Message content intended for the application. (Optional).
     * @param collapseKey GCM collapse_key parameter (Optional).
     * @param timeToLive GCM time_to_live parameter (Optional).
     * @param delayWhileIdle GCM delay_while_idle parameter (Optional).
     * @return JSON encoded GCM message.
     */
    fun createJsonMessage(to: String?, messageId: String?, payload: MutableMap<String, String>,
            collapseKey: String?, timeToLive: Long?, delayWhileIdle: Boolean): String {
        return createJsonMessage(createAttributeMap(to, messageId, payload, collapseKey, timeToLive, delayWhileIdle))
    }

    fun createJsonMessage(map: MutableMap<String, Any>): String {
        return JSONValue.toJSONString(map)
    }

    fun createAttributeMap(to: String?, messageId: String?, payload: MutableMap<String, String>,
            collapseKey: String?, timeToLive: Long?, delayWhileIdle: Boolean?): MutableMap<String, Any> {
        val message = hashMapOf<String, Any>()
        if (to != null) {
            message.put("to", to)
        }
        if (collapseKey != null) {
            message.put("collapse_key", collapseKey)
        }
        if (timeToLive != null) {
            message.put("time_to_live", timeToLive)
        }
        if (delayWhileIdle != null && delayWhileIdle) {
            message.put("delay_while_idle", true)
        }
        if (messageId != null) {
            message.put("message_id", messageId)
        }
        message.put("data", payload)
        return message
    }

    /**
     * Creates a JSON encoded ACK message for an upstream message received from
     * an application.
     *
     * @param to RegistrationId of the device who sent the upstream message.
     * @param messageId messageId of the upstream message to be acknowledged to
     * CCS.
     * @return JSON encoded ack.
     */
    fun createJsonAck(to: String, messageId: String): String {
        val message = HashMap<String, String>()
        message.put("message_type", "ack")
        message.put("to", to)
        message.put("message_id", messageId)
        return JSONValue.toJSONString(message)
    }

    /**
     * Creates a JSON encoded NACK message for an upstream message received from
     * an application.
     *
     * @param to RegistrationId of the device who sent the upstream message.
     * @param messageId messageId of the upstream message to be acknowledged to
     * CCS.
     * @return JSON encoded nack.
     */
    fun createJsonNack(to: String, messageId: String): String {
        val message = HashMap<String, String>()
        message.put("message_type", "nack")
        message.put("to", to)
        message.put("message_id", messageId)
        return JSONValue.toJSONString(message)
    }

    /**
     * Connects to GCM Cloud Connection Server using the supplied credentials.
     * @throws XMPPException
     */
    private fun connect() {
        config = ConnectionConfiguration(Config.GCM_SERVER, Config.GCM_PORT)
        config?.setSecurityMode(SecurityMode.enabled)
        config?.setReconnectionAllowed(true)
        config?.setRosterLoadedAtLogin(false)
        config?.setSendPresence(false)
        config?.setSocketFactory(SSLSocketFactory.getDefault())

        // NOTE: Set to true to launch a window with information about packets sent and received
        config?.setDebuggerEnabled(debuggable)

        connection = XMPPConnection(config)
        connection?.connect()

        connection?.addConnectionListener(object: ConnectionListener {

            override fun reconnectionSuccessful() {
                logger.info("Reconnecting..")
            }

            override fun reconnectionFailed(e: Exception) {
                logger.log(Level.INFO, "Reconnection failed.. ", e)
            }

            override fun reconnectingIn(seconds: Int) {
                logger.log(Level.INFO, "Reconnecting in %d secs", seconds)
            }

            override fun connectionClosedOnError(e: Exception) {
                logger.log(Level.INFO, "Connection closed on error.")
            }

            override fun connectionClosed() {
                logger.info("Connection closed.")
            }
        })

        // Handle incoming packets
        connection?.addPacketListener(object: PacketListener {

            override fun processPacket(packet: Packet) {
                logger.log(Level.INFO, "Received: " + packet.toXML())
                val incomingMessage = packet as Message
                val gcmPacket = incomingMessage.getExtension(Config.GCM_NAMESPACE) as GcmPacketExtension
                val json = gcmPacket.json
                try {
                    val jsonMap = JSONValue.parseWithException(json) as MutableMap<String, Any>
                    handleMessage(jsonMap)
                } catch (e: ParseException) {
                    logger.log(Level.SEVERE, "Error parsing JSON " + json, e)
                } catch (e: Exception) {
                    logger.log(Level.SEVERE, "Couldn't send echo.", e)
                }
            }
        }, PacketTypeFilter(Message::class.java))

        // Log all outgoing packets
        connection?.addPacketInterceptor(object: PacketInterceptor {
            override fun interceptPacket(packet: Packet) {
                logger.log(Level.INFO, "Sent: {0}", packet.toXML())
            }
        }, PacketTypeFilter(Message::class.java))

        connection?.login(projectId + "@gcm.googleapis.com", apiKey)
        logger.log(Level.INFO, "logged in: " + projectId)
    }

    fun handleMessage(jsonMap: Map<String, Any>) {
        // present for "ack"/"nack", null otherwise
        val messageType = jsonMap.get("message_type")

        if (messageType == null) {
            val msg = getMessage(jsonMap)
            // Normal upstream data message
            try {
                handleIncomingDataMessage(msg)
                // Send ACK to CCS
                val ack = createJsonAck(msg.mFrom, msg.mMessageId)
                send(ack)
            }
            catch (e: Exception) {
                // Send NACK to CCS
                val nack = createJsonNack(msg.mFrom, msg.mMessageId)
                send(nack)
            }
        } else if ("ack".equals(messageType.toString())) {
            // Process Ack
            handleAckReceipt(jsonMap)
        } else if ("nack".equals(messageType.toString())) {
            // Process Nack
            handleNackReceipt(jsonMap)
        } else {
            logger.log(Level.WARNING, "Unrecognized message type (%s)", messageType.toString())
        }
    }
}
