assembly:
	sbt app/assembly wallet/assembly

package:
	sbt app/universal:packageBin

docker:
	sbt app/docker

clean:
	sbt clean

format:
	sbt format

unit-test:
	sbt test

integration-test:
	sbt it:test

test-all: clean format unit-test integration-test
	sbt doc

publish-local:
	sbt publishLocal

# make release version=x.y.z
release:
	project/version.sh $(version)
#	git add -A && git commit -m "$(version)"
#	git tag v$(version)
#	git push cheng v$(version)
#	git push cheng master

run:
	sbt app/run

benchmark:
	sbt "benchmark/jmh:run -i 3 -wi 3 -f1 -t1 .*Bench.*"
