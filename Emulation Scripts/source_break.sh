#!/bin/bash
cd ../src
javac -d ../bin MSTCP/vegas/more/*.java
cd ../bin

DIRECTORY="break"
SOURCES=2
PATHS=1
CONNECTIONS=1
BATCH_SIZE=1
P_DROP=0.01
PACKET_LIMIT=300
FILE="me.jpg"

echo "Break Experiments: TCP (BASELINE)"

path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"1
PACKET_LIMIT=300
FILE="me.jpg"
echo $path
rm -r $path
mkdir -p $path
for i in {0..0}
do
    java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE1
PACKET_LIMIT=300
FILE="me.jpg"
done

SOURCES=2
PATHS=2
CONNECTIONS=2
BATCH_SIZE=1
P_DROP=0.01
PACKET_LIMIT=300
FILE="me.jpg"

echo "Break Experiments: MSMPTCP (2 Source, 2 Paths, 2 connections)"

path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"1
PACKET_LIMIT=300
FILE="me.jpg"
echo $path
rm -r $path
mkdir -p $path
for i in {0..0}
do
    java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE1
PACKET_LIMIT=300
FILE="me.jpg"
done

SOURCES=2
PATHS=2
CONNECTIONS=4
BATCH_SIZE=1
P_DROP=0.01
PACKET_LIMIT=300
FILE="me.jpg"

echo "Break Experiments: MSMPTCP (2 Sources, 2 Paths, 4 connections)"

path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"1
PACKET_LIMIT=300
FILE="me.jpg"
echo $path
rm -r $path
mkdir -p $path
for i in {0..0}
do
    java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE1
PACKET_LIMIT=300
FILE="me.jpg"
done


SOURCES=2
PATHS=1
CONNECTIONS=1
BATCH_SIZE=16
P_DROP=0.01
PACKET_LIMIT=300
FILE="me.jpg"

echo "Break Experiments: TCP with Network Coding (Batch Size 16)"

path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"1
PACKET_LIMIT=300
FILE="me.jpg"
echo $path
rm -r $path
mkdir -p $path
for i in {0..0}
do
    java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE1
PACKET_LIMIT=300
FILE="me.jpg"
done

SOURCES=1
PATHS=2
CONNECTIONS=2
BATCH_SIZE=16
P_DROP=0.01
PACKET_LIMIT=300
FILE="me.jpg"

echo "Break Experiments: MSMPTCP with Network Coding (2 Source, 2 Paths, 2 Connections, Batch Size 16)"

path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"1
PACKET_LIMIT=300
FILE="me.jpg"
echo $path
rm -r $path
mkdir -p $path
for i in {0..0}
do
    java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE1
PACKET_LIMIT=300
FILE="me.jpg"
done

SOURCES=2
PATHS=2
CONNECTIONS=4
BATCH_SIZE=16
P_DROP=0.01
PACKET_LIMIT=300
FILE="me.jpg"

echo "Break Experiments: MSMPTCP with Network Coding (2 Sources, 2 Paths, 4 Connections, Batch Size 16)"

path="../evaluation/data/${DIRECTORY}/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"1
PACKET_LIMIT=300
FILE="me.jpg"
echo $path
rm -r $path
mkdir -p $path
for i in {0..0}
do
    java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $DIRECTORY $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP $PACKET_LIMIT $i $FILE1
PACKET_LIMIT=300
FILE="me.jpg"
done