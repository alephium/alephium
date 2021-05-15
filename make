#!/usr/bin/env python
import argparse, multiprocessing, os, shutil, subprocess, sys, tempfile, uuid, hashlib

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

def mining_action_call(args):
    (host, port, action) = args
    cmd_tmp = """curl -X POST http://{}:{}/miners?action={}-mining"""
    cmd = cmd_tmp.format(host, port, action)
    return (host, port, cmd, run_capture(cmd))

def mining_action_call_all(action):
    nodes = get_env_int('NODES')
    deployedNodes = get_env_default_int('DEPLOYED_NODES', 0)

    calls = []
    for node in range(deployedNodes, deployedNodes + nodes):
        port = (port_start + 3000) + node
        calls.append(('127.0.0.1', port, action))

    pool = multiprocessing.Pool(multiprocessing.cpu_count())
    results = pool.map(mining_action_call, calls)
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
            format       format code to comply with our code style
            test         Run the test suite
            itest        Run the integration test suite
            benchmark    Run the benchmark suite
            package      Produce the project deliverable
            publish      Publish locally the projects libraries
            release      Release a new version of the project
            docker       Build the docker image

            run          Run a local testnet
            kill         kill a local running testnet
            mining       Run a mining action: start or stop
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
        run_exit('sbt clean app/assembly')

    def build(self):
        run_exit('sbt app/stage')

    def format(self):
        run_exit('sbt scalafmtSbt scalafmt test:scalafmt scalastyle test:scalastyle it:scalafmt it:scalastyle doc')

    def test(self):
        run_exit('sbt scalafmtSbtCheck scalafmtCheck test:scalafmtCheck scalastyle test:scalastyle coverage test coverageReport doc')

    def itest(self):
        run_exit('sbt it:scalafmtCheck it:scalastyle it:test')

    def package(self):
        run_exit('sbt app/universal:packageBin')

    def publish(self):
        run_exit('sbt publishLocal')

    def release(self):
        run_exit('sbt release')

    def docker(self):
        run_exit('sbt clean app/docker')

    def benchmark(self):
        run_exit('sbt \"benchmark/jmh:run -i 3 -wi 3 -f1 -t1 .*Bench.*\"')

    def run(self):
        tempdir = tempfile.gettempdir()
        groups = get_env_int('GROUPS')
        brokerNum = get_env_default_int('BROKER_NUM', groups)
        nodes = get_env_int('NODES')
        assert(groups % brokerNum == 0 and nodes % brokerNum == 0)

        print("Logs dir: " + tempdir + "/alephium")

        deployedNodes = get_env_default_int('DEPLOYED_NODES', 0)

        for node in range(deployedNodes, deployedNodes + nodes):
            port = 9973 + node
            rpcPort = port + 1000
            wsPort = port + 2000
            restPort = port + 3000
            bindAddress = "127.0.0.1:" + str(port)
            coordinatorAddress = "127.0.0.1:" + str(9973 + node // brokerNum * brokerNum)
            brokerId = node % brokerNum
            print("Starting a new node")
            print("node-{}: {} (coordinator: {})".format(str(brokerId), bindAddress, coordinatorAddress))

            bootstrap = ""
            if node // brokerNum > 0:
                bootstrap = "127.0.0.1:" + str(9973 + node % brokerNum)

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
                  network.network-type = "testnet"
                }}
            """.format(brokerId, brokerNum, groups, bindAddress, bindAddress, bindAddress, coordinatorAddress,
                    rpcPort, wsPort, restPort, bootstrap)

            userConfPath = '{}/user.conf'.format(nodedir)

            if os.path.isfile(userConfPath):
                os.remove(userConfPath)

            userConfFile = open(userConfPath, 'w')
            userConfFile.write(userConf)
            userConfFile.close()

            run('ALEPHIUM_HOME={} '\
              'nice -n 19 ./app/target/universal/scripts/bin/alephium-app &> {}/console.log &'\
              .format(nodedir, nodedir))

    def mining(self, params):
        action = params[0]
        mining_action_call_all(action)

    def kill(self):
        run("ps aux | grep -i org.alephium.app.Boot | awk '{print $2}' | xargs kill 2> /dev/null")

    def clean(self):
        run('sbt clean')

if __name__ == '__main__':
    AlephiumMake()
