package edu.unc.mapseq.messaging.ncnexus.variantcalling;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.MessageConsumer;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NCNEXUSVariantCallingMessageService {

    private final Logger logger = LoggerFactory.getLogger(NCNEXUSVariantCallingMessageService.class);

    private Connection connection;

    private Session session;

    private ConnectionFactory connectionFactory;

    private NCNEXUSVariantCallingMessageListener messageListener;

    private String destinationName;

    public NCNEXUSVariantCallingMessageService() {
        super();
    }

    public void start() throws Exception {
        logger.info("ENTERING start()");
        this.connection = connectionFactory.createConnection();
        this.session = this.connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Destination destination = this.session.createQueue(this.destinationName);
        MessageConsumer consumer = this.session.createConsumer(destination);
        consumer.setMessageListener(getMessageListener());
        this.connection.start();
    }

    public void stop() throws Exception {
        logger.info("ENTERING stop()");
        if (this.session != null) {
            this.session.close();
        }
        if (this.connection != null) {
            this.connection.stop();
            this.connection.close();
        }
    }

    public ConnectionFactory getConnectionFactory() {
        return connectionFactory;
    }

    public void setConnectionFactory(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    public NCNEXUSVariantCallingMessageListener getMessageListener() {
        return messageListener;
    }

    public void setMessageListener(NCNEXUSVariantCallingMessageListener messageListener) {
        this.messageListener = messageListener;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public void setDestinationName(String destinationName) {
        this.destinationName = destinationName;
    }

}
