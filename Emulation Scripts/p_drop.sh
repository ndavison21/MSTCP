#!/bin/bash
cd ../src
javac -d ../bin MSTCP/vegas/more/*.java
cd ../bin

SOURCES=1
PATHS=1
CONNECTIONS=1
BATCH_SIZE=1
P_DROP=0.0

echo "P_DROP Experiments: TCP (BASELINE)"
for P_DROP in {0..0.5..0.01}
do
    path="../evaluation/data/timing/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
    echo $path
    rm -r $path
    mkdir -p $path
    for i in {0..0}
    do
        java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP -1 $i
    done
done

SOURCES=1
PATHS=2
CONNECTIONS=2
BATCH_SIZE=1
P_DROP=0.0

echo "P_DROP Experiments: MSMPTCP (1 Source, 2 Paths)"
for P_DROP in {0..0.5..0.01}
do
    path="../evaluation/data/timing/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
    echo $path
    rm -r $path
    mkdir -p $path
    for i in {0..0}
    do
        java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP -1 $i
    done
done

SOURCES=2
PATHS=2
CONNECTIONS=4
BATCH_SIZE=1
P_DROP=0.0

echo "P_DROP Experiments: MSMPTCP (2 Sources, 2 Paths)"
for P_DROP in {0..0.5..0.01}
do
    path="../evaluation/data/timing/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
    echo $path
    rm -r $path
    mkdir -p $path
    for i in {0..0}
    do
        java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP -1 $i
    done
done


SOURCES=1
PATHS=1
CONNECTIONS=1
BATCH_SIZE=16
P_DROP=0.0

echo "P_DROP Experiments: TCP with Network Coding (Batch Size 16)"
for P_DROP in {0..0.5..0.01}
do
    path="../evaluation/data/timing/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
    echo $path
    rm -r $path
    mkdir -p $path
    for i in {0..0}
    do
        java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP -1 $i
    done
done

SOURCES=1
PATHS=2
CONNECTIONS=2
BATCH_SIZE=16
P_DROP=0.0

echo "P_DROP Experiments: MSMPTCP with Network Coding (1 Source, 2 Paths, Batch Size 16)"
for P_DROP in {0..0.5..0.01}
do
    path="../evaluation/data/timing/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
    echo $path
    rm -r $path
    mkdir -p $path
    for i in {0..0}
    do
        java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP -1 $i
    done
done

SOURCES=2
PATHS=2
CONNECTIONS=4
BATCH_SIZE=16
P_DROP=0.0

echo "P_DROP Experiments: MSMPTCP with Network Coding (2 Sources, 2 Paths, Batch Size 16)"
for P_DROP in {0..0.5..0.01}
do
    path="../evaluation/data/timing/s${SOURCES}_p${PATHS}_c${CONNECTIONS}_b${BATCH_SIZE}_p${P_DROP}/"
    echo $path
    rm -r $path
    mkdir -p $path
    for i in {0..0}
    do
        java -Djava.util.logging.SimpleFormatter.format='%5$s%6$s%n' MSTCP.vegas.more.Experiment $SOURCES $PATHS $CONNECTIONS $BATCH_SIZE $P_DROP -1 $i
    done
done