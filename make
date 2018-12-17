#!/usr/bin/env python3
import argparse, os, tempfile

parser = argparse.ArgumentParser(description='Alephium Make')

parser.add_argument('goal', type=str)

args = parser.parse_args()

def rpc_call(host, port, method, params):
    json = """{{"jsonrpc":"2.0","id":"curltext","method":"{}","params": {}}}"""
    cmd = """curl --data-binary '{}' -H 'content-type:text/plain;' http://{}:{}/"""
    run(cmd.format(json.format(method, params), host, port))

def rpc_call_all(method, params):
    for node in range(0, int(os.environ['nodes'])):
        port = 8080 + node
        rpc_call('localhost', port, method, params)

def run(cmd):
    print(cmd)
    os.system(cmd)

if args.goal == 'build':
    run('sbt clean app/stage')

elif args.goal == 'test':
    run('sbt clean scalafmtSbt coverage test coverageReport doc')

elif args.goal == 'package':
    run('sbt app/universal:packageBin')

elif args.goal == 'benchmark':
    run('sbt \"benchmark/jmh:run -i 3 -wi 3 -f1 -t1 .*Bench.*\"')

elif args.goal == 'run':

    tempdir = tempfile.gettempdir()

    for node in range(0, int(os.environ['nodes'])):
        port = 9973 + node
        groups = int(os.getenv('groups'))
        main_group = node % groups

        homedir = "{}/alephium/node-{}".format(tempdir, node)

        if not os.path.exists(homedir):
            os.makedirs(homedir)


        run('mainGroup={} port={} bootstrap=localhost:9973 ALEPHIUM_HOME={} ./app/target/universal/stage/bin/boot &> {}/console.log &'.format(main_group, port, homedir, homedir))

elif args.goal == 'mine':
    rpc_call_all("mining/start", "[]")

elif args.goal == 'kill':
    run("ps aux | grep -i org.alephium | awk '{print $2}' | xargs sudo kill 2> /dev/null")

elif args.goal == 'genesis':
    run('mainGroup=0 ./app/target/universal/stage/bin/prepare-genesis')
