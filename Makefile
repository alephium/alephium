test:
	sbt clean scalafmt test:scalafmt scalastyle test:scalastyle coverage test coverageReport

build:
	sbt app/stage

package:
	sbt app/universal:packageBin

root:
	sbt 'app/run ${nodes} 9973'

mine:
	index=0; port=8080 ; while [[ $$index -lt $$nodes ]] ; do \
        echo $$index $$port ; \
		curl -X PUT localhost:$$port/mining ; \
        ((index = index + 1)) ; \
        ((port = port + 1)) ; \
    done
