#!/usr/bin/env python3
import argparse, multiprocessing, os, pathlib, shutil, subprocess, sys, tempfile, secrets, hashlib

port_start = 9973

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

def rpc_call(args):
    (host, port, method, params) = args
    json = """{{"jsonrpc":"2.0","id": 0,"method":"{}","params": {}}}"""
    cmd_tmp = """curl --data-binary '{}' -H 'content-type:application/json' http://{}:{}"""
    cmd = cmd_tmp.format(json.format(method, params), host, port)
    return (host, port, cmd, run_capture(cmd))

def rpc_call_all(method, params):
    nodes = get_env_int('NODES')
    deployedNodes = get_env_default_int('DEPLOYED_NODES', 0)

    calls = []
    for node in range(deployedNodes, deployedNodes + nodes):
        port = (port_start + 1000) + node
        calls.append(('localhost', port, method, params))

    pool = multiprocessing.Pool(multiprocessing.cpu_count())
    results = pool.map(rpc_call, calls)
    pool.close()

    for (host, port, cmd, result) in results:
        print("\n[RPC]-[{}@{}]: {}".format(host, port, cmd))
        print(result.stderr.decode('utf-8'))
        print(result.stdout.decode('utf-8'))

def run(cmd):
    return os.system(cmd)

def run_exit(cmd):
    status = run(cmd)
    sys.exit(status > 0)

def run_capture(args):
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, shell=True)

class AlephiumMake(object):
    def __init__(self):
        parser = argparse.ArgumentParser(
            usage='''make <command> [<args>]

            clean        Clean the project workspace
            build        Build the project
            test         Run the test suite
            itest        Run the integration test suite
            benchmark    Run the benchmark suite
            package      Produce the project deliverable
            release      Release a new version of the project

            run          Run a local testnet
            kill         kill a local running testnet
            rpc method params    rpc call to local testnet
        ''')

        parser.add_argument('command', nargs='*', help='Subcommand to run')
        args = parser.parse_args()
        if not hasattr(self, args.command[0]):
            print('Unrecognized command')
            parser.print_help()
            exit(1)
        if len(args.command) == 1:
            getattr(self, args.command[0])()
        else:
            getattr(self, args.command[0])(args.command[1:])

    def assembly(self):
        run_exit('sbt app-server/assembly')

    def build(self):
        run_exit('sbt app-server/stage')

    def test(self):
        run_exit('sbt scalafmtSbt scalafmt test:scalafmt scalastyle test:scalastyle coverage test coverageReport doc')

    def itest(self):
        run_exit('sbt it:scalafmt it:scalastyle it:test')

    def package(self):
        run_exit('sbt app-server/universal:packageBin')

    def publish(self):
        run_exit('sbt publishLocal')

    def release(self):
        run_exit('sbt release')

    def benchmark(self):
        run_exit('sbt \"benchmark/jmh:run -i 3 -wi 3 -f1 -t1 .*Bench.*\"')

    def run(self):
        tempdir = tempfile.gettempdir()
        homedir = str(pathlib.Path.home())
        groups = get_env_int('GROUPS')
        brokerNum = get_env_default_int('BROKER_NUM', groups)
        nodes = get_env_int('NODES')
        assert(groups % brokerNum == 0 and nodes % brokerNum == 0)

        print("Logs dir: " + tempdir + "/alephium")

        deployedNodes = get_env_default_int('DEPLOYED_NODES', 0)

        apiKey = secrets.token_urlsafe(32)
        apiKeyHash = hashlib.sha256(str.encode(apiKey)).hexdigest()
        print("Api key: " + apiKey)

        for node in range(deployedNodes, deployedNodes + nodes):
            port = 9973 + node
            rpcPort = port + 1000
            wsPort = port + 2000
            restPort = port + 3000
            bindAddress = "localhost:" + str(port)
            coordinatorAddress = "localhost:" + str(9973 + node // brokerNum * brokerNum)
            brokerId = node % brokerNum
            print("Starting a new node")
            print("node-{}: {} (coordinator: {})".format(str(brokerId), bindAddress, coordinatorAddress))

            bootstrap = ""
            if node // brokerNum > 0:
                bootstrap = "localhost:" + str(9973 + node % brokerNum)

            nodedir = "{}/alephium/node-{}".format(tempdir, node)

            if not os.path.exists(nodedir):
                os.makedirs(nodedir)

            userConf = """
                alephium {{
                  broker {{
                    broker-id = {}
                    broker-num = {}
                    groups = {}
                  }}
                  network {{
                    bind-address = "{}"
                    external-address = "{}"
                    internal-address  = "{}"
                    coordinator-address    = "{}"
                    rpc-port = {}
                    ws-port = {}
                    rest-port = {}
                  }}
                  discovery {{
                    bootstrap = "{}"
                  }}
                  api {{
                    api-key-hash = {}
                  }}
                }}
            """.format(brokerId, brokerNum, groups, bindAddress, bindAddress, bindAddress, coordinatorAddress,
                    rpcPort, wsPort, restPort, bootstrap, apiKeyHash)

            userConfPath = '{}/user.conf'.format(nodedir)

            if os.path.isfile(userConfPath):
                os.remove(userConfPath)

            userConfFile = open(userConfPath, 'w')
            userConfFile.write(userConf)
            userConfFile.close()

            run('ALEPHIUM_HOME={} '\
              'nice -n 19 ./app-server/target/universal/stage/bin/app-server &> {}/console.log &'\
              .format(nodedir, nodedir))

    def rpc(self, params):
        method = params[0]
        args = params[1] if len(params) > 1 else "{}"
        rpc_call_all(method, args)

    def kill(self):
        run("ps aux | grep -i org.alephium.appserver.Boot | awk '{print $2}' | xargs kill 2> /dev/null")

    def clean(self):
        run('sbt clean')

if __name__ == '__main__':
    AlephiumMake()
