#!/bin/sh

CONFIG_TEMPLATES=/config_templates
VOLUME="/var/run/artemis/"
BASE=$(dirname $0)
INSTANCE=$($BASE/get_free_instance.py $VOLUME)

if [ ! -d "$INSTANCE" ]; then
    $ARTEMIS_HOME/bin/artemis create $INSTANCE --user admin --password admin --role admin --allow-anonymous
    cp $CONFIG_TEMPLATES/broker_header.xml /tmp/broker.xml
    if [ -n "$QUEUE_NAME" ]; then
        cat $CONFIG_TEMPLATES/broker_queue.xml >> /tmp/broker.xml
    elif [ -n "$TOPIC_NAME" ]; then
        cat $CONFIG_TEMPLATES/broker_topic.xml >> /tmp/broker.xml
    fi
    cat $CONFIG_TEMPLATES/broker_footer.xml >> /tmp/broker.xml

    envsubst < /tmp/broker.xml > $INSTANCE/etc/broker.xml
fi

exec $INSTANCE/bin/artemis run
