#!/usr/bin/env python3
import argparse, os, tempfile

parser = argparse.ArgumentParser(description='Alephium Make')

parser.add_argument('goal', type=str)

args = parser.parse_args()

tempdir = tempfile.gettempdir()

def run(cmd):
    print(cmd)
    os.system(cmd)

if args.goal == 'build':
    run('sbt clean app/stage')

elif args.goal == 'test':
    run('sbt clean scalafmtSbt coverage test coverageReport')

elif args.goal == 'package':
    run('sbt app/universal:packageBin')

elif args.goal == 'benchmark':
    run('sbt \"benchmark/jmh:run -i 3 -wi 3 -f1 -t1 .*Bench.*\"')

elif args.goal == 'run':
    logdir = "{}/alephium-log".format(tempdir)

    if not os.path.exists(logdir):
        os.makedirs(logdir)

    for node in range(0, int(os.environ['nodes'])):
        port = 9973 + node
        run('./app/target/universal/stage/bin/boot {} &> {}/{}.txt &'.format(port, logdir, port))

elif args.goal == 'mine':
    for node in range(0, int(os.environ['nodes'])):
        port = 8080 + node
        run('curl -X PUT localhost:{}/mining'.format(port))

elif args.goal == 'kill':
    run("ps aux | grep -i org.alephium | awk '{print $2}' | xargs sudo kill 2> /dev/null")

elif args.goal == 'genesis':
    run('./app/target/universal/stage/bin/prepare-genesis')
