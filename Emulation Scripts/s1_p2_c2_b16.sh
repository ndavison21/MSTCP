#!/bin/bash
cd ../src
javac -d ../bin MSTCP/vegas/more/*.java
cd ../bin
for i in {0..1000} do
    java -Djava.util.logging.SimpleFormatter.format="%5$s%6$s%n MSTCP.vegas.more.Experiment 1 2 2 16 $i
done
