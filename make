#!/usr/bin/env python3
import argparse, os, tempfile, sys

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
    json = """{{"jsonrpc":"2.0","id": 0,"method":"{}","params": {}}}"""
    cmd = """curl --data-binary '{}' -H 'content-type:application/json' http://{}:{}"""
    run(cmd.format(json.format(method, params), host, port))

def rpc_call_all(method, params):
    nodes = get_env_int('nodes')
    batch = get_env_default_int('batch', 0)
    for node in range(batch * nodes, (batch + 1) * nodes):
        port = (port_start + 1000) + node
        rpc_call('localhost', port, method, params)

def run(cmd):
    print(cmd)
    os.system(cmd)

class AlephiumMake(object):
    def __init__(self):
        parser = argparse.ArgumentParser(
            usage='''make <command> [<args>]

   clean        Clean the project workspace
   build        Build the project
   test         Run the test suite
   benchmark    Run the benchmark suite
   package      Produce the project deliverable


   run          Run a local testnet
   mining_start Start mining on local testnet
   mining_stop  Stop mining on local testnet
   kill         kill a local running testnet
''')
        parser.add_argument('command', help='Subcommand to run')
        args = parser.parse_args(sys.argv[1:2])
        if not hasattr(self, args.command):
            print('Unrecognized command')
            parser.print_help()
            exit(1)
        getattr(self, args.command)()

    def build(self):
        run('sbt app/stage')

    def test(self):
        run('sbt scalafmtSbt scalafmt test:scalafmt scalastyle test:scalastyle coverage test coverageReport doc')

    def package(self):
        run('sbt app/universal:packageBin')

    def benchmark(self):
        run('sbt \"benchmark/jmh:run -i 3 -wi 3 -f1 -t1 .*Bench.*\"')

    def run(self):
        tempdir = tempfile.gettempdir()
        groups = get_env_int('groups')
        brokerNum = get_env_default_int('brokerNum', groups)
        nodes = get_env_int('nodes')
        assert(groups % brokerNum == 0 and nodes % brokerNum == 0)

        batch = get_env_default_int('batch', 0)
        for node in range(batch * nodes, (batch + 1) * nodes):
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

    def mining_start(self):
        rpc_call_all("mining_start", "[]")

    def mining_stop(self):
        rpc_call_all("mining_stop", "[]")

    def kill(self):
        run("ps aux | grep -i org.alephium | awk '{print $2}' | xargs kill 2> /dev/null")

    def clean(self):
        run('sbt clean')

if __name__ == '__main__':
    AlephiumMake()
