import pylab as plt
import numpy as np

exp = 10


plt.figure()
plt.grid(True)
plt.xlabel('Time (ns)')
plt.ylabel('Throughput')


base_colours = iter([ 'autumn', 'winter', 'spring', 'summer'])
colours = dict()


for i in range(0, exp):
    filename = '../data/throughput/s1_c2_b16/experiment_{}.log'.format(i)
    print filename + '\n'
    start = 0

    with open(filename) as f:
        first = True            # all times are relative to the first so need to record it
        throughput_all = dict() # store separate throughputs for each path

        for line in f.readlines():
            time, port, cwnd, rtt, p = (line.rstrip('\n')).split(' ')
            time = int(time)
            port = int(port)
            cwnd = int(cwnd)
            rtt  = float(rtt)
            p    = float(p)

            if (first):
                start = time
                first = False


            if (not(port in throughput_all.keys())): # initialise the dict if necessary
                throughput_all[port] = dict()
            if (not (port in colours.keys())):
                c = plt.cm.get_cmap(next(base_colours))
                colours[port] = iter([c(j) for j in np.linspace(0, 1, exp)])

            if (rtt == 0):
                throughput_all[port][time - start] = 0.0
            else:
                throughput_all[port][time - start] = cwnd / rtt

    for port in throughput_all.keys():
        elements = sorted(throughput_all[port].items())
        x,y = zip(*elements)
        c = colours[port]
        plt.plot(x, y, color=next(c))

plt.savefig('../visualisation/throughput/s1_c2_b16.pdf')
plt.close()

