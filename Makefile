build:
	sbt stage

alephium-fun:
	mkdir -p log
	port=9973 target/universal/stage/bin/alephium-fun &> log/00.txt &
	port=9974 target/universal/stage/bin/alephium-fun &> log/01.txt &
	port=9975 target/universal/stage/bin/alephium-fun &> log/10.txt &
	port=9976 target/universal/stage/bin/alephium-fun &> log/11.txt &
