/usr/sbin/useradd -c "OpenDJ Directory Server user" -d /usr/local/opendj \
        -s /bin/bash opendj > /dev/null 2>&1
passwd -l opendj        
