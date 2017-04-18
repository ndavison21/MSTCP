wget http://sourceforge.net/projects/jnetpcap/files/jnetpcap/1.3/jnetpcap-1.3.0-1.ubuntu.x86_64.tgz
mv jnetpcap-1.3.0-1.ubuntu.x86_64.tgz ../..
cd ../..
tar xzvf jnetpcap-1.3.0-1.ubuntu.x86_64.tgz
cd jnetpcap-1.3.0
cp lbjnetpcap.so /usr/lib
cp jnetpcap.jar /usr/share/java
echo "CLASSPATH=\".:/usr/share/java/jnetpcap.jar\"" >> /etc/environment
echo "LD_LIBRARY_PATH=\"/usr/lib\"" >> /etc/environment