# normal user "opendj" cannot listen on ports < 1024
sed -i'' -e 's/ds-cfg-listen-port: 389/ds-cfg-listen-port: 1389/' /usr/local/opendj/config/config.ldif

# create init.d script and start on next boot
/usr/local/opendj/bin/create-rc-script --outputfile /etc/init.d/opendj --userName opendj
/sbin/chkconfig --add opendj
/sbin/chkconfig --level 345 opendj on
