package io.enmasse.systemtest.executor.client.proton.jms;

import io.enmasse.systemtest.executor.client.AbstractClient;
import io.enmasse.systemtest.executor.client.Argument;
import io.enmasse.systemtest.executor.client.ArgumentMap;
import io.enmasse.systemtest.executor.client.ClientType;

import java.util.Arrays;
import java.util.List;

public class JMSClientReceiver extends AbstractClient {
    public JMSClientReceiver(){
        super(ClientType.CLI_JAVA_PROTON_JMS_RECEIVER);
    }

    @Override
    protected void fillAllowedArgs() {
        allowedArgs.add(Argument.CONN_RECONNECT);
        allowedArgs.add(Argument.CONN_RECONNECT_INTERVAL);
        allowedArgs.add(Argument.CONN_RECONNECT_LIMIT);
        allowedArgs.add(Argument.CONN_RECONNECT_TIMEOUT);
        allowedArgs.add(Argument.CONN_HEARTBEAT);
        allowedArgs.add(Argument.CONN_SSL_CERTIFICATE);
        allowedArgs.add(Argument.CONN_SSL_PRIVATE_KEY);
        allowedArgs.add(Argument.CONN_SSL_PASSWORD);
        allowedArgs.add(Argument.CONN_SSL_TRUST_STORE);
        allowedArgs.add(Argument.CONN_SSL_VERIFY_PEER);
        allowedArgs.add(Argument.CONN_SSL_VERIFY_PEER_NAME);
        allowedArgs.add(Argument.CONN_MAX_FRAME_SIZE);
        allowedArgs.add(Argument.CONN_ASYNC_ACKS);
        allowedArgs.add(Argument.CONN_ASYNC_SEND);
        allowedArgs.add(Argument.CONN_AUTH_MECHANISM);
        allowedArgs.add(Argument.CONN_AUTH_SASL);
        allowedArgs.add(Argument.CONN_CLIENT_ID);
        allowedArgs.add(Argument.CONN_CLOSE_TIMEOUT);
        allowedArgs.add(Argument.CONN_CONN_TIMEOUT);
        allowedArgs.add(Argument.CONN_DRAIN_TIMEOUT);
        allowedArgs.add(Argument.CONN_SSL_TRUST_ALL);
        allowedArgs.add(Argument.CONN_SSL_VERIFY_HOST);

        allowedArgs.add(Argument.TX_SIZE);
        allowedArgs.add(Argument.TX_ACTION);
        allowedArgs.add(Argument.TX_ENDLOOP_ACTION);

        allowedArgs.add(Argument.LINK_DURABLE);
        allowedArgs.add(Argument.LINK_AT_MOST_ONCE);
        allowedArgs.add(Argument.LINK_AT_LEAST_ONCE);
        allowedArgs.add(Argument.CAPACITY);

        allowedArgs.add(Argument.LOG_LIB);
        allowedArgs.add(Argument.LOG_STATS);
        allowedArgs.add(Argument.LOG_MESSAGES);

        allowedArgs.add(Argument.BROKER);
        allowedArgs.add(Argument.ADDRESS);
        allowedArgs.add(Argument.USERNAME);
        allowedArgs.add(Argument.PASSWORD);
        allowedArgs.add(Argument.COUNT);
        allowedArgs.add(Argument.CLOSE_SLEEP);
        allowedArgs.add(Argument.TIMEOUT);
        allowedArgs.add(Argument.DURATION);

        allowedArgs.add(Argument.SELECTOR);
        allowedArgs.add(Argument.RECV_BROWSE);
        allowedArgs.add(Argument.ACTION);
        allowedArgs.add(Argument.PROCESS_REPLY_TO);
    }

    @Override
    protected ArgumentMap transformArguments(ArgumentMap args) {
        args = javaBrokerTransformation(args);
        return args;
    }

    @Override
    protected List<String> transformExecutableCommand(String executableCommand) {
        return Arrays.asList("java", "-jar", executableCommand, "receiver");
    }
}
