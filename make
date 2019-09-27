#!/usr/bin/env python3
import argparse, os, tempfile

port_start = 9973

parser = argparse.ArgumentParser(description='Alephium Make')

parser.add_argument('goal', type=str)

args = parser.parse_args()

def get_env(key):
    return os.environ[key]

def get_env_int(key):
    return int(os.environ[key])

def get_env_default(key, default):
    if key in os.environ:
        return os.environ[key]
    else:
        return default

def get_env_default_int(key, default):
    return int(get_env_default(key, default))

def rpc_call(host, port, method, params):
    json = """{{"jsonrpc":"2.0","id":"curltext","method":"{}","params": {}}}"""
    cmd = """curl --data-binary '{}' -H 'content-type:application/json' http://{}:{}"""
    run(cmd.format(json.format(method, params), host, port))

def rpc_call_all(method, params):
    for node in range(0, get_env_int('nodes')):
        port = (port_start + 1000) + node
        rpc_call('localhost', port, method, params)

def run(cmd):
    print(cmd)
    os.system(cmd)

if args.goal == 'build':
    run('sbt clean app/stage')

elif args.goal == 'test':
    run('sbt clean scalafmtSbt scalastyle test:scalastyle coverage test coverageReport doc')

elif args.goal == 'package':
    run('sbt clean app/universal:packageBin')

elif args.goal == 'benchmark':
    run('sbt \"benchmark/jmh:run -i 3 -wi 3 -f1 -t1 .*Bench.*\"')

elif args.goal == 'run':

    tempdir = tempfile.gettempdir()
    groups = get_env_int('groups')
    brokerNum = get_env_default_int('brokerNum', groups)
    nodes = get_env_int('nodes')
    assert(groups % brokerNum == 0 and nodes % brokerNum == 0)

    for node in range(0, nodes):
        port = 9973 + node
        publicAddress = "localhost:" + str(port)
        masterAddress = "localhost:" + str(9973 + node // brokerNum * brokerNum)
        brokerId = node % brokerNum

        bootstrap = ""
        if node // brokerNum > 0:
            bootstrap = "localhost:" + str(9973 + node % brokerNum)
        print("{}, {}".format(node, bootstrap))

        homedir = "{}/alephium/node-{}".format(tempdir, node)

        if not os.path.exists(homedir):
            os.makedirs(homedir)

        run('brokerNum={} brokerId={} publicAddress={} masterAddress={} bootstrap={} ALEPHIUM_HOME={} ./app/target/universal/stage/bin/app &> {}/console.log &'.format(brokerNum, brokerId, publicAddress, masterAddress, bootstrap, homedir, homedir))

elif args.goal == 'mine':
    rpc_call_all("mining_start", "[]")

elif args.goal == 'kill':
    run("ps aux | grep -i org.alephium | awk '{print $2}' | xargs kill 2> /dev/null")
