package xmpp

/**
 * Created by marcelo on 22/09/15.
 */
interface PayloadProcessor {

    fun handleMessage(msg: CcsMessage)

}