build:
	sbt app/stage

package:
	sbt app/universal:packageBin

root:
	mkdir -p log
	index=0; port=9973 ; while [[ $$index -lt $$nodes ]] ; do \
        echo $$index $$port ; \
		groups=$$groups app/target/universal/stage/bin/app $$port &> log/$$port.txt & \
        ((index = index + 1)) ; \
        ((port = port + 1)) ; \
    done

mine:
	index=0; port=8080 ; while [[ $$index -lt $$nodes ]] ; do \
        echo $$index $$port ; \
		curl -X PUT localhost:$$port/mining ; \
        ((index = index + 1)) ; \
        ((port = port + 1)) ; \
    done
