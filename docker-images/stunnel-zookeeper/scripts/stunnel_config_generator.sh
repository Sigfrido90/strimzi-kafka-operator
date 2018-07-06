#!/bin/bash
	      
CERTS=/etc/stunnel/certs/

echo "foreground = yes"
#echo "pid=/tmp/stunnel.pid"
echo "debug = info"
 
declare -a PORTS=("2888" "3888")
for port in "${PORTS[@]}"
do
NODE=1
 while [ $NODE -le $ZOOKEEPER_NODE_COUNT ]; do
	        
		PEER=${BASE_HOSTNAME}-$((NODE-1))
		KEY_AND_CERT=${CERTS}${PEER}	
		PEM=/tmp/${PEER}.pem

		# Not necessarily the way to create pem file, might have to use openssl
		cat ${KEY_AND_CERT}.crt ${KEY_AND_CERT}.key > ${PEM}
		chmod 640 ${PEM}

		# Stunnel client configuration
		cat <<-EOF
		[${PEER})]
		client=yes
		#cert=${PEM}
		CAfile=${CERTS}internal-ca.crt
		accept=127.0.0.1:$(expr $port \* 10 + $NODE)
		connect=${PEER}.${BASE_FQDN}:$port

		EOF
		let NODE=NODE+1
  done
	# Zookeeper port where stunnel forwards recieved traffic
	CONNECTOR_PORT=$(expr $port \* 10)

	# Stunnel listener configuration
	cat <<-EOF
	[listener-$port]
	client=no
	cert=${PEM}
	accept=127.0.0.1:$port
	connect=127.0.0.1:$CONNECTOR_PORT

	EOF
done
