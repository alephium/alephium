assembly:
	sbt app/assembly wallet/assembly ralphc/assembly

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
	project/release.sh $(version)

run:
	sbt app/run

update: update-openapi update-builtin-doc

update-openapi:
	ALEPHIUM_ENV=test sbt "tools/runMain org.alephium.tools.OpenApiUpdate"

update-builtin-doc:
	sbt "tools/runMain org.alephium.tools.BuiltInFunctions"

benchmark:
	sbt "benchmark/jmh:run -i 3 -wi 3 -f1 -t1 .*Bench.*"
