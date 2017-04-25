# MSTCP
Master's Project - Nathanael Davison - nd359 [at] cam.ac.uk

This project is an attempt to combine multiple pathing, as in multi-path TCP (MPTCP), with multiple sourcing, as in bitorrent/swarms. This multi-source TCP (MSTCP) will allow load balancing on the granularity of individual packets, rather than connections. It also allows improvements in terms of robustness and throughput.

However MSTCP, like TCP, will suffer in performance when there are losses. Network coding allows us to maximise the degrees of freedom that resource pooling can enjoy. Network coding spreads data over both packets and flows - allowing and encouraging mixing of data at intermediate nodes. This allows us to cope with (or rather, remove) traffic imbalances and therefore reduce hot spots in the network. Multi-Sourcing allows us to create more opportunities for coding and approach the optimum efficiency.

The results of this work will be the design and implementation of a protocol for allowing multi-source TCP with linear coding.