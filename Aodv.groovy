import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.net.*
import org.arl.unet.mac.*
import org.arl.unet.nodeinfo.*
import java.lang.*

/*  Reference:
*
*   C. Perkins, E. Belding-Royer, and S. Das, "Ad-hoc On-Demand Distance Vector (AODV)
*   Routing," RFC 3561, 2003.
*
*/

class Aodv extends UnetAgent
{
    private AgentID node, phy, rtr, mac

    private int myAddr

    private int temp = 0                   // Initial Broadcast request ID number (temp) will be zero.
    private int seqn = 0                   // Initial sequential number would be zero.
    private int rreqcount = 0               // Monitors the RREQ rate per second.
    private int rerrcount = 0               // Monitors the RERR rate per second.
    private int firstActiveRouteFlag = 0    // Monitors the FIRST ACTIVE ROUTE instance to initiate HELLO PACKET CHECK.
    private long lastbroadcast = 0          // Last BROADCAST time to facilitate HELLO PACKET check.

    // CONSTANT values ->

    // Types of packet:
    private final static int RREQ                 = 0x01
    private final static int RREP                 = 0x02
    private final static int RERR                 = 0x03
    private final static int HELLO                = 0x04

    // Active status of a route:
    private final static boolean ACTIVE           = true
    private final static boolean INACTIVE         = false

    private final static boolean NON_HELLO_PACKET = true
    private final static boolean HELLO_PACKET     = false  

    // Valid sequence number:
    private final static int VALID_DSN      = 1
    private final static int INVALID_DSN    = 0
    private final static int UNKNOWN_DSN    = -1

    // Important parameters:
    private final static int MAX_NUMBER_OF_TXS  = 2
    private final static int ALLOWED_HELLO_LOSS = 2

    private final static int DATA_PACKET    = 1
    private final static int CTRL_PACKET    = 0

    private final static int MAX_RREQ_RATE  = 10
    private final static int MAX_RERR_RATE  = 10
    private final static int NET_DIAMETER   = 35

    // Various timeout values:
    private final static long ACTIVE_ROUTE_TIMEOUT = 3000
    private final static long HELLO_INTERVAL       = 1000
    private final static long NODE_TRAVERSAL_TIME  = 40
    private final static long NET_TRAVERSAL_TIME   = 2*NODE_TRAVERSAL_TIME*NET_DIAMETER

    // Different Protocols used:
    private final static int ROUTING_PROTOCOL = Protocol.ROUTING
    private final static int RM_PROTOCOL      = Protocol.ROUTE_MAINTENANCE
    private final static int DATA_PROTOCOL    = Protocol.DATA

    private final static double inf = Double.POSITIVE_INFINITY  // Infinity for Hop Count.

    private final static int HOP_ZERO         = 0
    private final static int HOP_ONE          = 1

    private final static PDU rreqpacket = PDU.withFormat
    {
        uint32('sourceAddr')
        uint32('sourceSeqNum')
        uint32('broadcastId')
        uint32('destAddr')
        uint32('destSeqNum')
        uint32('hopCount')
    }

    private final static PDU rreppacket = PDU.withFormat
    {
        uint32('sourceAddr')
        uint32('destAddr')
        uint32('destSeqNum')
        uint32('hopCount')
        uint32('expirationTime')
    }

    private final static PDU rmpacket = PDU.withFormat
    {
        uint32('type')
        uint32('destAddr')
        uint32('destSeqNum')
        uint32('hopCount')
        uint32('expirationTime')
    }

    private final static PDU dataMsg = PDU.withFormat
    {
        uint16('source')
        uint16('destination')
    }

    // Routing Table of the nodes.
    private class RoutingInfo
    {
        private int destinationAddress
        private int validdsn
        private int dsn
        private int nexHop
        private int numHops
        private long expTime
        private boolean active
    }

    private class AttemptingHistory
    {
        private int destinationAddr
        private int num
    }

    private class PacketHistory
    {
        private int osna
        private int ridn
        private int hoco
    }

    private class PacketError
    {
        private int errnode
        private int errnum
    }

    private class Precursor
    {
        private int finalnode
        private int neighbournode
    }

    private class TxReserve
    {
        private TxFrameReq txreq
        private ReservationReq resreq
    }

    ArrayList<Aodv.RoutingInfo> myroutingtable       = new ArrayList<Aodv.RoutingInfo>()
    ArrayList<Aodv.AttemptingHistory> attemptHistory = new ArrayList<Aodv.AttemptingHistory>()
    ArrayList<Aodv.PacketHistory> myPacketHistory    = new ArrayList<Aodv.PacketHistory>()
    ArrayList<Aodv.Precursor> precursorList          = new ArrayList<Aodv.Precursor>()
    ArrayList<Aodv.TxReserve> reservationTable       = new ArrayList<Aodv.TxReserve>()
    ArrayList<Aodv.PacketError> errorTable           = new ArrayList<Aodv.PacketError>()

    @Override
    void setup()
    {
        register Services.ROUTE_MAINTENANCE
    }

    @Override
    void startup()
    {
        node = agentForService(Services.NODE_INFO)
        myAddr = node.Address
        mac = agentForService(Services.MAC)
        rtr = agentForService(Services.ROUTING)
        phy = agentForService(Services.PHYSICAL)
        subscribe phy

        refreshCount()
        tables()
    }

    private void refreshCount()
    {
        add new TickerBehavior(1000, {  // To refresh the RREQ and RERR count after every second.
            rreqcount = 0
            rerrcount = 0
            })
    }

    private void tables()
    {
        add new WakerBehavior(590000, {
            println(myAddr+"'s Routing table  -> "+myroutingtable.destinationAddress+myroutingtable.nexHop)
            println(myAddr+"'s Precursor List -> "+precursorList.finalnode+precursorList.neighbournode)
            })
    }

    private void rxDisable()
    {
        // Disable Receiver.
        ParameterReq req = new ParameterReq(agentForService(Services.PHYSICAL))
        req.get(PhysicalParam.rxEnable)
        ParameterRsp rsp = (ParameterRsp) request(req, 1000)            
        rsp.set(PhysicalParam.rxEnable,false) 
    }

    private void rxEnable()
    {
        // Enable Receiver.
        ParameterReq req = new ParameterReq(agentForService(Services.PHYSICAL))
        req.get(PhysicalParam.rxEnable)
        ParameterRsp rsp = (ParameterRsp)request(req, 1000)         
        rsp.set(PhysicalParam.rxEnable,true) 
    }

    // Broadcast RREQ for Route Discovery.
    private void sendRreqBroadcast(int destination)
    {
        int dsnvalue = getDsn(destination)    // DSN value in the RT.

        PacketHistory ph = new PacketHistory(osna: myAddr, ridn: temp, hoco: HOP_ZERO)
        myPacketHistory.add(ph)               // Before broadcasting the RREQ, save the details of this packet.

        def bytes = rreqpacket.encode(sourceAddr: myAddr, sourceSeqNum: seqn, broadcastId: temp, destAddr: destination, destSeqNum: dsnvalue, hopCount: HOP_ZERO)
        TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
        sendMessage(tx, CTRL_PACKET)
        println(myAddr+" STARTING A ROUTE DISCOVERY.")
        add new WakerBehavior(routeDiscTimeout(destination)*NET_TRAVERSAL_TIME, {  // Page 15. Binary exponential backoff.
            routeDiscoveryCheck(destination)
            })
    }
    // Binary exponential backoff for retransmissions.
    private double routeDiscTimeout(int des)
    {
        for (int i = 0; i < attemptHistory.size(); i++)
        {
            if (attemptHistory.get(i).destinationAddr == des)
            {
                return Math.pow(2, attemptHistory.get(i).num - 1)
            }
        }
    }

    // Check for Route discovery success.
    private void routeDiscoveryCheck(int dest)
    {
        if (nodePresent(dest) == true && getActiveStatus(dest) == ACTIVE && getExpirationTime(dest) > currentTimeMillis())
        {
            println("ROUTE FOUND IN THE RT")
            return
        }
        else    // Do another Route Discovery.
        {
            println("ROUTE NOT FOUND. DO ANOTHER ROUTE DISCOVERY.")
            def rdp = agentForService(Services.ROUTE_MAINTENANCE)
            rdp << new RouteDiscoveryReq(to: dest, maxHops: 50, count: 1)
        }
    }
    // Sending Reservation Requests to MAC protocol.
    private void sendMessage(TxFrameReq txReq, int packetType)
    {
        if (packetType == 0)    // CTRL packets.
        {
            ReservationReq rs = new ReservationReq(to: txReq.to, duration: controlMsgDuration/1000)
            TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
            reservationTable.add(tr)
            mac << rs
        }
        else if (packetType == 1)   // DATA packets.
        {
            ReservationReq rs = new ReservationReq(to: txReq.to, duration: dataMsgDuration/1000)
            TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
            reservationTable.add(tr)
            mac << rs
        }
    }

    // After every HELLO_INTERVAL, I check the LAST BROADCAST of a node.
    private void helloPacketCheck()
    {
        add new TickerBehavior(HELLO_INTERVAL, {
            int checkActive = 0
            for (int i = 0; i < myroutingtable.size(); i++)
            {
                // If there is any ACTIVE route, I check whether I BROADCASTED any packet in the last HELLO_INTERVAL.
                if (myroutingtable.get(i).active == ACTIVE && myroutingtable.get(i).expTime > currentTimeMillis())
                {
                    checkActive = 1
                    if (lastbroadcast < currentTimeMillis() - HELLO_INTERVAL)   // Last broadcast happened before HELLO_INTERVAL.
                    {
                        long expiry = currentTimeMillis() + ALLOWED_HELLO_LOSS*HELLO_INTERVAL   // Page 22.

                        TxFrameReq tx = new TxFrameReq(         // Preparing the HELLO packet.
                            to:         Address.BROADCAST,
                            type:       Physical.CONTROL,
                            protocol:   RM_PROTOCOL,
                            data:       rmpacket.encode(type: HELLO, destAddr: myAddr, destSeqNum: seqn, hopCount: HOP_ZERO, expirationTime: expiry)
                            )
                        sendMessage(tx, CTRL_PACKET)
                        println(myAddr+" HELLO at "+currentTimeMillis())
                    }
                    break
                }
            }
            if (checkActive == 0)   // There are no ACTIVE ROUTES in this node's RT.
            {
                firstActiveRouteFlag = 0
            }
            })
    }

    // In case I discover an expired/unreachable link, I increment the DSN
    // of all the destinations using that next hop and mark the route as INACTIVE.
    private void routeUnreachable(int finaldest)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == finaldest)
            {
                myroutingtable.get(i).dsn++ // should I keep it equal to the DSN in the RERR packet? Yes, increment it. Page 11.
                myroutingtable.get(i).active = INACTIVE
                myroutingtable.get(i).expTime = 0
                break
            }
        }
    }

    // Sending RERR to all precursors of the affected destination.
    private void routeErrorPacket(int target)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).nexHop == target)
            {
                myroutingtable.get(i).expTime = 0           // Route exp time as zero.
                myroutingtable.get(i).active = INACTIVE     // Route becomes INACTIVE.
                int so = ++myroutingtable.get(i).dsn        // Incrementing the DSN.
                println(myAddr+" SENDING a RERR packet for "+target)

                PacketError pe = new PacketError(errnode: target, errnum: so)
                errorTable.add(pe)      // Add this RERR packet in Error Table history.

                // Preparing the RERR packet.
                def bytes = rmpacket.encode(type: RERR, destAddr: target, destSeqNum: so, hopCount: inf, expirationTime: 0)
                TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: RM_PROTOCOL, data: bytes)
                sendMessage(tx, CTRL_PACKET)

                // Wait for this long, then DELETE the route if still INACTIVE.
                add new WakerBehavior(ALLOWED_HELLO_LOSS*HELLO_INTERVAL, {
                    if (getActiveStatus(target) == INACTIVE || getExpirationTime(target) <= currentTimeMillis())
                    {
                        removeRouteEntry(target)
                    }
                    })
                break
            }
        }
    }

    // Remove a route entry along with the precursor list.
    private void removeRouteEntry(int dest)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == dest)
            {
                println(myAddr+" DELETING ROUTE FOR DEST: "+dest)
                myroutingtable.remove(i)    // remove route entry.
                break
            }
        }

        for (int i = 0; i < precursorList.size(); i++)
        {
            if (precursorList.get(i).finalnode == dest)
            {
                precursorList.remove(i)     // remove corresponding precursor entry.
                break
            }
        }
    }

    // When a route is used to transmit DATA/RREP packets, its life is extended by ACTIVE_ROUTE_TIMEOUT.
    // I update all the routes using this next hop.
    private void extendRouteLife(int adjacentnode)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).nexHop == adjacentnode)
            {
                println(myAddr+" EXTENDED ROUTE LIFE of "+adjacentnode+" NEXTHOP for FD: "+myroutingtable.get(i).destinationAddress)
                myroutingtable.get(i).active  = ACTIVE
                myroutingtable.get(i).expTime = Math.max(getExpirationTime(adjacentnode), currentTimeMillis() + ACTIVE_ROUTE_TIMEOUT)   // Page 21.
                break
            }
        }
    }

    /*  If the OS is already there in the RT, update its details in case
    *   the sequence number of packet < the sequence number in the RT, or
    *   the seq numbers of both the packet and the RT are equal, but the packet has a smaller hop count than that in the RT, or
    *   the seq number in the RT is UNKNOWN.
    */
    private void routingDetailsUpdate(  int origin, int from, int seqnum, int hcv, long life, int statusone, boolean statustwo,
                                        int statusthree, boolean statusfour, boolean nonhello, boolean rreq)
    {
        // 1) If the OS directly sent me this message.
        if (origin == from)
        {
            int flag = 0
            for (int i = 0; i < myroutingtable.size(); i++)    // This node is already there in the RT.
            {
                if (myroutingtable.get(i).destinationAddress == origin)
                {
                    flag = 1
                    if (myroutingtable.get(i).dsn < seqnum || myroutingtable.get(i).validdsn == INVALID_DSN || 
                        (myroutingtable.get(i).dsn == seqnum && (hcv < myroutingtable.get(i).numHops || myroutingtable.get(i).active == INACTIVE)))
                    {   // For these conditions, see page 16: 6.5., 1. for source and page 20: 6.7., (ii) for dest.
                        myroutingtable.get(i).validdsn = statusone  // always VALID.
                        myroutingtable.get(i).dsn      = seqnum
                        myroutingtable.get(i).nexHop   = from
                        myroutingtable.get(i).numHops  = hcv
                        myroutingtable.get(i).expTime  = life
                        myroutingtable.get(i).active   = statustwo
                    }
                    break
                }
            }
            // If this node was never there in my RT, I add its details.
            if (flag == 0)
            {
                RoutingInfo ri = new RoutingInfo(
                    destinationAddress: origin,
                    validdsn:           statusone,
                    dsn:                seqnum,
                    nexHop:             from,
                    numHops:            hcv,
                    expTime:            life,
                    active:             statustwo
                    )
                myroutingtable.add(ri)
                if (nonhello && rreq)   // To purge any unwanted route entries generated during a Route Discovery.
                {
                    routeValidityCheck(life - currentTimeMillis(), origin)
                }
            }
            if (nonhello && statustwo)  // The ROUTE is ACTIVE.
            {
                if (firstActiveRouteFlag == 0)  // HELLO Packet Check has not been initiated.
                {
                    firstActiveRouteFlag = 1
                    helloPacketCheck()      // Start HELLO PACKET check.
                }
                activeStatusCheck(origin)   // The route is ACTIVE, keep a regular check on its active status.
            }

            if (!nonhello)  // It's a HELLO packet-generated route.
            {
                int fella = origin
                add new TickerBehavior(ALLOWED_HELLO_LOSS*HELLO_INTERVAL, {
                    if (getActiveStatus(fella) == INACTIVE || getExpirationTime(fella) < currentTimeMillis())
                    {
                        if (getPrecursor(fella) != 0)   // There are PRECURSORS for this Destination node.
                        {
                            routeErrorPacket(fella)
                        }
                        else    // There are no PRECURSORS for this Destination node.
                        {
                            removeRouteEntry(fella)
                        }
                    }
                    })
            }
        }
        // 2) The OS did not send this message directly, it came from an intermediate node.
        else
        {
           int flag = 0
            for (int i = 0; i < myroutingtable.size(); i++)
            {
                if (myroutingtable.get(i).destinationAddress == origin) // This node is already there in the RT.
                {
                    flag = 1
                    if (myroutingtable.get(i).dsn < seqnum || myroutingtable.get(i).validdsn == INVALID_DSN || 
                        (myroutingtable.get(i).dsn == seqnum && (hcv < myroutingtable.get(i).numHops || myroutingtable.get(i).active == INACTIVE)))
                    {   // For these conditions, see page 16: 6.5., 1. for source and page 20: 6.7., (ii) for dest.
                        myroutingtable.get(i).validdsn = statusone
                        myroutingtable.get(i).dsn      = seqnum
                        myroutingtable.get(i).nexHop   = from
                        myroutingtable.get(i).numHops  = hcv
                        myroutingtable.get(i).expTime  = life
                        myroutingtable.get(i).active   = statustwo
                    }
                    break
                }
            }
            // If this node was never there in my RT, I add its details.
            if (flag == 0)
            {
                RoutingInfo ri = new RoutingInfo(
                    destinationAddress: origin,
                    validdsn:           statusone,
                    dsn:                seqnum,
                    nexHop:             from,
                    numHops:            hcv,
                    expTime:            life,
                    active:             statustwo
                    )  
                myroutingtable.add(ri)
                if (nonhello && rreq)               // This shall be done only once.
                {
                    routeValidityCheck(life - currentTimeMillis(), origin)
                }
            }
            if (nonhello && statustwo)  // The ROUTE is ACTIVE.
            {
                if (firstActiveRouteFlag == 0)  // HELLO Packet check has not been initiated.
                {
                    firstActiveRouteFlag = 1
                    helloPacketCheck()      // Start HELLO PACKET check.
                }
                activeStatusCheck(origin)   // The route is ACTIVE, keep a regular check on its ACTIVE STATUS.
            }

            int verification = 0
            for (int i = 0; i < myroutingtable.size(); i++)      // If the next hop towards the origin is already there in the RT.
            {
                if (myroutingtable.get(i).destinationAddress == from)
                {
                    verification = 1
                    if (getDsnStatus(from) == INVALID_DSN) // The DSN status shall be changed only when it is INVALID.
                    {
                        myroutingtable.get(i).validdsn = statusthree   
                    }
                    myroutingtable.get(i).dsn     = getDsn(from)    // Either some value or UNKNOWN.
                    myroutingtable.get(i).nexHop  = from
                    myroutingtable.get(i).numHops = HOP_ONE
                    myroutingtable.get(i).expTime = life
                    myroutingtable.get(i).active  = statusfour
                    break
                }
            }
            if (verification == 0)
            {
                RoutingInfo ri = new RoutingInfo(
                    destinationAddress: from,
                    validdsn:           statusthree,
                    dsn:                UNKNOWN_DSN,        // Keep the DSN of this guy as UNKNOWN for now.
                    nexHop:             from,
                    numHops:            HOP_ONE,
                    expTime:            life,
                    active:             statusfour
                    )
                myroutingtable.add(ri)
                if (nonhello && rreq)               // This shall be done only once.
                {
                    routeValidityCheck(life - currentTimeMillis(), from)
                }
            }
            if (nonhello && statusfour)  // The ROUTE is ACTIVE.
            {
                if (firstActiveRouteFlag == 0)  // HELLO Packet check has not been initiated.
                {
                    firstActiveRouteFlag = 1
                    helloPacketCheck()      // Start HELLO PACKET check.
                }
                activeStatusCheck(from)     // The route is ACTIVE, keep a regular check on its active status.
            }
        }
    }

    // To purge any unwanted entries added during Route discovery.
    private void routeValidityCheck(long timegap, int node)
    {
        add new WakerBehavior(timegap, {
            for (int i = 0; i < myroutingtable.size(); i++)
            {
                if (myroutingtable.get(i).destinationAddress == node)
                {
                    if (myroutingtable.get(i).expTime <= currentTimeMillis() || myroutingtable.get(i).active == INACTIVE)
                    {
                        myroutingtable.remove(i)
                        println(myAddr+" Removed FD "+node+" at "+currentTimeMillis())
                    }
                    break
                }
            }
            })
    }

    // After every ACTIVE_ROUTE_TIMEOUT seconds, the ACTIVE STATUS of the route is CHECKED.
    private void activeStatusCheck(int node)
    {
        add new TickerBehavior(ACTIVE_ROUTE_TIMEOUT, {
            for (int i = 0; i < myroutingtable.size(); i++)
            {
                if (myroutingtable.get(i).destinationAddress == node)
                {
                    if (myroutingtable.get(i).expTime < currentTimeMillis() || myroutingtable.get(i).active == INACTIVE)
                    {
                        routeErrorPacket(node)      // Notify the other NODES about this INACTIVE route.
                    }
                    break
                }
            }
            })
    }

    private boolean nodePresent(int node)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == node)
            {
                return true
            }
        }
        return false
    }

    private int getDsn(int destination)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == destination)
            {
                return myroutingtable.get(i).dsn
            }
        }
        return UNKNOWN_DSN
    }

    private int getHopCount(int destination)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == destination)
            {
                return myroutingtable.get(i).numHops
            }
        }
        return inf
    }

    private int getDsnStatus(int destination)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == destination)
            {
                return myroutingtable.get(i).validdsn
            }
        }
        return INVALID_DSN
    }

    private boolean getActiveStatus(int destination)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == destination)
            {
                return myroutingtable.get(i).active
            }
        }
        return INACTIVE   
    }

    private long getExpirationTime(int destination)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == destination)
            {
                return myroutingtable.get(i).expTime
            }
        }
        return 0   
    }

    private int getPrecursor(int dest)
    {
        for (int i = 0; i < precursorList.size(); i++)
        {
            if (precursorList.get(i).finalnode == dest)
            {
                return precursorList.get(i).neighbournode
            }
        }
        return 0
    }

    private void precursorAddition(int fn, int nn)
    {
        for (int i = 0; i < precursorList.size(); i++)
        {
            if (precursorList.get(i).finalnode == fn && precursorList.get(i).neighbournode == nn)
            {
                return      // If this precursor pair is already there, do not add it.
            }
        }
        Precursor pc = new Precursor(finalnode: fn, neighbournode: nn)
        precursorList.add(pc)
    }

    /*  0: Max no. of transmissions not yet reached, but the node has been searched before.
    *   1: Node is searching for the first time. Do a route discovery.
    *   2: Maximum number of transmissions reached for this node. Refuse the request.
    */
    private int retransmissionCheck(int destination)
    {
        for (int i = 0; i < attemptHistory.size(); i++)
        {
            if (attemptHistory.get(i).destinationAddr == destination)
            {
                int txs = attemptHistory.get(i).num        // Number of transmissions for this destination.
                if (txs == MAX_NUMBER_OF_TXS)
                {
                    return 2
                }
                if (txs < MAX_NUMBER_OF_TXS)
                {
                    return 0
                }
            }
        }
        // Either this destination was never searched or this is the first ever search by this node.
        return 1
    }

    @Override
    Message processRequest(Message msg)
    {
        if (msg instanceof RouteDiscoveryReq)
        {
            int fd = msg.to     //  The Final Destination.
            
            if (myroutingtable.size() == 0) // 1) My RT is empty, check for how many times the destination has already been searched.
            {
                int reTxCheck = retransmissionCheck(fd)

                if (reTxCheck == 0 && rreqcount <= MAX_RREQ_RATE) // 1) This dest has been searched before, but it hasn't reached its max txs yet.
                {
                    for (int i = 0; i < attemptHistory.size(); i++)
                    {
                        if (attemptHistory.get(i).destinationAddr == fd)
                        {
                            rreqcount++                     // Incrementing the RREQ count.
                            attemptHistory.get(i).num++     // Increment the value of attempts for this destination; a new Route Discovery is starting.
                            temp++                          // Increase the request ID.
                            seqn++                          // Incrementing the sequence number for a Route Discovery.                  
                            sendRreqBroadcast(fd)           // Send an RREQ Broadcast.           
                            return new Message(msg, Performative.AGREE)
                        }
                    }
                }

                else if (reTxCheck == 1 && rreqcount <= MAX_RREQ_RATE)    // 2) This dest is being searched for the first time.
                {
                    rreqcount++                         // Incrementing the RREQ count.
                    AttemptingHistory tr = new AttemptingHistory(destinationAddr: fd, num: 1)
                    attemptHistory.add(tr)              // Added this destination in the attempting table.
                    temp++                              // Incrementing the request ID.
                    seqn++                              // Incrementing the sequence number for a Route Discovery.
                    sendRreqBroadcast(fd)               // Send an RREQ Broadcast.
                    return new Message(msg, Performative.AGREE)
                }

                else if (reTxCheck == 2)    // 3) Max txs limit reached. The node is unreachable, so refuse.
                {
                    return new Message(msg, Performative.REFUSE)
                }
            }

            else    // 2) In case my RT has some entries, search for the Desired destination first.
            {
                /*   If my RT has some entries, check for destination first:
                *    1) If found, it should be active. Send a notification;
                *    2) If not, do a Route discovery if the number of re-transmissions permits.
                */
                for (int i = 0; i < myroutingtable.size(); i++)
                {
                    if (myroutingtable.get(i).destinationAddress == fd && myroutingtable.get(i).active == ACTIVE && myroutingtable.get(i).expTime > currentTimeMillis())
                    {
                        int ko = myroutingtable.get(i).nexHop           // Destination found in the RT and the route is active.
                        int jo = myroutingtable.get(i).numHops          // Number of hops.
                        rtr << new RouteDiscoveryNtf(to: fd, nextHop: ko, hops: jo, reliability: true)  // Sending the Route discovery notification.
                        return new Message(msg, Performative.AGREE)
                    }
                }

                // Destination was not found in the RT. Do a check on the number of re-transmissions.
                int reTxCheck = retransmissionCheck(fd)

                if (reTxCheck == 0 && rreqcount <= MAX_RREQ_RATE) // 1) This dest has been searched before, but it hasn't reached its max txs yet.
                {
                    for (int i = 0; i < attemptHistory.size(); i++)
                    {
                        if (attemptHistory.get(i).destinationAddr == fd)
                        {
                            rreqcount++                         // Incrementing the RREQ count.
                            attemptHistory.get(i).num++         // Increment the value of transmissions for this destination.
                            temp++                              // Increase temp (request ID number), as a new route discovery is gonna start.
                            seqn++                              // Incrementing the sequence number for a Route Discovery.
                            sendRreqBroadcast(fd)               // Send an RREQ Broadcast.
                            return new Message(msg, Performative.AGREE)
                        }
                    }
                }
                
                else if (reTxCheck == 1 && rreqcount <= MAX_RREQ_RATE)    // 2) This dest is being searched for the first time.
                {
                    rreqcount++                         // Incrementing the RREQ count.
                    AttemptingHistory tr = new AttemptingHistory(destinationAddr: fd, num: 1)
                    attemptHistory.add(tr)              // Added this destination in the attempting table.
                    temp++                              // Incrementing the request ID number.
                    seqn++                              // Incrementing the sequence number for a Route Discovery.
                    sendRreqBroadcast(fd)               // Send an RREQ Broadcast.
                    return new Message(msg, Performative.AGREE)
                }

                else if (reTxCheck == 2)    // 3) Max txs limit reached. The node is unreachable, so refuse.
                {
                    return new Message(msg, Performative.REFUSE)
                }
            }
        }
        return null
    }

    @Override
    void processMessage(Message msg)
    {
        if (msg instanceof ReservationStatusNtf)
        {
            if (msg.status == ReservationStatus.START)
            {
                for (int i = 0; i < reservationTable.size(); i++)
                {
                    if (msg.requestID == reservationTable.get(i).resreq.requestID)
                    {
                        phy << reservationTable.get(i).txreq
                        reservationTable.remove(i)
                        break
                    }
                }
            }

            if (msg.status == ReservationStatus.FAILURE)
            {
                for (int i = 0; i < reservationTable.size(); i++)
                {
                    if (msg.requestID == reservationTable.get(i).resreq.requestID)
                    {
                        reservationTable.remove(i)
                        break
                    }
                }
            }
        }

        if (msg instanceof TxFrameNtf)
        {
            if (msg.getRecipient() == Address.BROADCAST)
            {
                lastbroadcast = currentTimeMillis()     // Keeps track of the last broadcast time.
            }
        }

        // This is for Routing packets: RREQ and RREP.
        if (msg instanceof RxFrameNtf && msg.type == Physical.CONTROL)
        {
            if (msg.getTo() == Address.BROADCAST && msg.protocol == ROUTING_PROTOCOL)   // RREQ
            {
                def info = rreqpacket.decode(msg.data)

                int originalsource = info.sourceAddr
                int originaldest   = info.destAddr
                int hopcountvalue  = info.hopCount
                int osseqnum       = info.sourceSeqNum
                int odseqnum       = info.destSeqNum
                int requestIDNo    = info.broadcastId

                if (myPacketHistory.size() == 0)    // 1) First ever packet received by this node.
                {
                    if (++hopcountvalue > NET_DIAMETER)
                    {
                        return
                    }
                    PacketHistory ph = new PacketHistory(osna: originalsource, ridn: requestIDNo, hoco: hopcountvalue)
                    myPacketHistory.add(ph)

                    if (originaldest == myAddr)     // 1) I am the Final Destination.
                    {
                        seqn = Math.max(seqn, odseqnum)                                 // Sequence number update.
                        long clock = currentTimeMillis() + ACTIVE_ROUTE_TIMEOUT         // New expiry time for this route.
                        routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, ACTIVE, INVALID_DSN, ACTIVE, NON_HELLO_PACKET, false)
                        println(myAddr+" is the FD. Sending an RREP back. "+originalsource+' exptime: '+clock)
                        long expiry = currentTimeMillis() + 2*ACTIVE_ROUTE_TIMEOUT      // Page 18, 6.6.1.

                        TxFrameReq tx = new TxFrameReq(                         // Preparing the RREP packet.
                            to:         msg.from,
                            type:       Physical.CONTROL,
                            protocol:   ROUTING_PROTOCOL,
                            data:       rreppacket.encode(  sourceAddr: originalsource, destAddr: originaldest,
                                                            destSeqNum: seqn, hopCount: HOP_ZERO, expirationTime: expiry)
                            )
                        sendMessage(tx, CTRL_PACKET)
                        return
                    }

                    else    // 2) I am not the Destination. Simply re-broadcast the RREQ packet.
                    {
                        long clock = Math.max(getExpirationTime(originalsource), currentTimeMillis() + 2*NET_TRAVERSAL_TIME - 2*hopcountvalue*NODE_TRAVERSAL_TIME)
                        // Page 17
                        routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, INACTIVE, INVALID_DSN, INACTIVE, NON_HELLO_PACKET, true)
                        int sop = Math.max(getDsn(originaldest), odseqnum)      // Sequence number to be transmitted. Page 17: Lastly,...
                        println(myAddr+" is not the FD. Re-broadcasting the RREQ. "+originalsource+' exptime: '+clock)
                        TxFrameReq tx = new TxFrameReq(                         // Re-broadcast the RREQ packet.
                            to:         Address.BROADCAST,
                            type:       Physical.CONTROL,
                            protocol:   ROUTING_PROTOCOL,
                            data:       rreqpacket.encode(  sourceAddr: originalsource, sourceSeqNum: osseqnum, broadcastId: requestIDNo,
                                                            destAddr: originaldest, destSeqNum: sop, hopCount: hopcountvalue)
                            )
                        sendMessage(tx, CTRL_PACKET)
                        return   
                    }
                }

                else    // 2) This is not the first packet I am receiving.
                {
                    if (++hopcountvalue > NET_DIAMETER) // Hop count should be < the NET DIAMETER.
                    {
                        return
                    }
                    // Doing a packet redundancy check first.
                    int cool = 0
                    for (int i = 0; i < myPacketHistory.size(); i++)
                    {   
                        // If a similar packet is found, check its hop count value.
                        if (myPacketHistory.get(i).ridn == requestIDNo && myPacketHistory.get(i).osna == originalsource)
                        {
                            cool = 1    // This packet is already there.
                            // If the hop count in the packet is smaller than that in the RT, accept it.
                            if (hopcountvalue < myPacketHistory.get(i).hoco)
                            {
                                myPacketHistory.get(i).hoco = hopcountvalue     // Updating the hop count.
                            }
                            else    // This packet is useless.
                            {
                                return
                            }
                            break
                        }
                    }

                    if (cool == 0)          // The packet was not found in the PH table. Add its details.
                    {
                        PacketHistory ph = new PacketHistory(osna: originalsource, ridn: requestIDNo, hoco: hopcountvalue)
                        myPacketHistory.add(ph)
                    }

                    // 1) I am the Destination.
                    if (myAddr == originaldest)
                    {
                        seqn = Math.max(seqn, odseqnum)                                 // Sequence number update.
                        long clock = currentTimeMillis() + ACTIVE_ROUTE_TIMEOUT         // New expiry time for this route.
                        routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, ACTIVE, INVALID_DSN, ACTIVE, NON_HELLO_PACKET, false)

                        long expiry = currentTimeMillis() + 2*ACTIVE_ROUTE_TIMEOUT              // Page 18, 6.6.1.

                        TxFrameReq tx = new TxFrameReq(                     // Preparing the RREP packet.
                            to:         msg.from,
                            type:       Physical.CONTROL,
                            protocol:   ROUTING_PROTOCOL,
                            data:       rreppacket.encode(  sourceAddr: originalsource, destAddr: originaldest,
                                                            destSeqNum: seqn, hopCount: HOP_ZERO, expirationTime: expiry)
                            )
                        sendMessage(tx, CTRL_PACKET)
                        return
                    }

                    // 2) I am not the final destination.
                    else
                    {
                        // Checking whether the link is bi-directional or not.

                        // 1) I have the route. The for loop contains statements for route availability.
                        for (int i = 0; i < myroutingtable.size(); i++)
                        {
                            // Checking the destination and the active status of the link in the RT.
                            if (myroutingtable.get(i).destinationAddress == originaldest && myroutingtable.get(i).active == ACTIVE && myroutingtable.get(i).expTime > currentTimeMillis())
                            {
                                // 1.1) If the route is Current. Send an RREP back to the OS.
                                if (myroutingtable.get(i).dsn >= odseqnum)
                                {
                                    long clock = currentTimeMillis() + ACTIVE_ROUTE_TIMEOUT     // New expiry value for the route.
                                    routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, ACTIVE, INVALID_DSN, ACTIVE, NON_HELLO_PACKET, false)

                                    int nextnode = myroutingtable.get(i).nexHop     // Next hop for the destination.

                                    precursorAddition(originaldest, msg.from)       // Precursor list updated for forward route entry.
                                    precursorAddition(originalsource, nextnode)     // Precursor list updated for reverse route entry.                                 

                                    int lo = myroutingtable.get(i).dsn              // Sequence number of the destination.
                                    int go = myroutingtable.get(i).numHops          // The hop count value for the OD from this node.
                                    long expiry = myroutingtable.get(i).expTime     // Expiry time for the RREP. See page 19.

                                    TxFrameReq tx = new TxFrameReq(                 // Preparing the RREP packet.
                                        to:         msg.from,
                                        type:       Physical.CONTROL,
                                        protocol:   ROUTING_PROTOCOL,
                                        data:       rreppacket.encode(  sourceAddr: originalsource, destAddr: originaldest,
                                                                        destSeqNum: lo, hopCount: go, expirationTime: expiry)
                                        )
                                    sendMessage(tx, CTRL_PACKET)
                                    /*
                                    TxFrameReq tx = new TxFrameReq(                 // Preparing the Gratuitous RREP packet.
                                        to:         nextnode
                                        type:       Physical.CONTROL
                                        protocol:   ROUTING_PROTOCOL
                                        data:       rreppacket.encode(  sourceAddr: originaldest, destAddr: originalsource,
                                                                        destSeqNum: osseqnum, hopCount: hopcountvalue, expirationTime: clock)
                                        )
                                    sendMessage(tx, CTRL_PACKET)
                                    */
                                    return
                                }

                                // 1.2) The route is not current. Re-broadcast the RREQ.
                                else if (myroutingtable.get(i).dsn < odseqnum)
                                {
                                    long clock = Math.max(getExpirationTime(originalsource), currentTimeMillis() + 2*NET_TRAVERSAL_TIME - 2*hopcountvalue*NODE_TRAVERSAL_TIME)
                                    // Page 17.
                                    routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, INACTIVE, INVALID_DSN, INACTIVE, NON_HELLO_PACKET, true)
                                    int sop = Math.max(getDsn(originaldest), odseqnum)      // Sequence number to be transmitted. Page 17: Lastly,...

                                    TxFrameReq tx = new TxFrameReq(                         // Preparing the RREQ packet for re-broadcast.
                                        to:         Address.BROADCAST,
                                        type:       Physical.CONTROL,
                                        protocol:   ROUTING_PROTOCOL,
                                        data:       rreqpacket.encode(  sourceAddr: originalsource, sourceSeqNum: osseqnum, broadcastId: requestIDNo,
                                                                        destAddr: originaldest, destSeqNum: sop, hopCount: hopcountvalue)
                                        )
                                    sendMessage(tx, CTRL_PACKET)
                                    return
                                }
                            }
                        }

                        // 2)   I do not have the Destination in my RT. Re-broadcast the RREQ.
                        long clock = Math.max(getExpirationTime(originalsource), currentTimeMillis() + 2*NET_TRAVERSAL_TIME - 2*hopcountvalue*NODE_TRAVERSAL_TIME)
                        // Page 17.
                        routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, INACTIVE, INVALID_DSN, INACTIVE, NON_HELLO_PACKET, true)
                        int sop = Math.max(getDsn(originaldest), odseqnum)          // Sequence number to be transmitted. Page 17: Lastly,...

                        TxFrameReq tx = new TxFrameReq(                             // Re-broadcast the RREQ packet.                           
                            to:         Address.BROADCAST,
                            type:       Physical.CONTROL,
                            protocol:   ROUTING_PROTOCOL,
                            data:       rreqpacket.encode(  sourceAddr: originalsource, sourceSeqNum: osseqnum, broadcastId: requestIDNo,
                                                            destAddr: originaldest, destSeqNum: sop, hopCount: hopcountvalue)
                            )
                        sendMessage(tx, CTRL_PACKET)
                        return
                    }
                }
            } // of RREQ

            // For RREP. The expiration time in the RREP should be more than the current time.
            if (msg.getTo() == node.Address && msg.protocol == ROUTING_PROTOCOL)
            {
                def info = rreppacket.decode(msg.data)

                int originalsource = info.sourceAddr
                int originaldest   = info.destAddr
                int odseqnum       = info.destSeqNum
                int hopcountvalue  = info.hopCount
                long exp           = info.expirationTime

                if (exp > currentTimeMillis() && getDsn(originaldest) <= odseqnum && ++hopcountvalue <= NET_DIAMETER)
                {
                    long clock = exp                // Same as that of the RREP packet. See page 21.
                    routingDetailsUpdate(originaldest, msg.from, odseqnum, hopcountvalue, exp, VALID_DSN, ACTIVE, INVALID_DSN, ACTIVE, NON_HELLO_PACKET, false)

                    if (myAddr == originalsource)   // 1) I am the OS.
                    {
                        println(myAddr+" ROUTE DISCOVERY OVER FOR "+originaldest)
                        rtr << new RouteDiscoveryNtf(to: originaldest, nextHop: msg.from, hops: hopcountvalue, reliability: true) // Send a RouteDiscoveryNtf.
                    }

                    else    // 2) I am not the OS.
                    {
                        println(myAddr+" not the OS. Sending RREP back to the OS.")
                        for (int i = 0; i < myroutingtable.size(); i++)
                        {
                            if (myroutingtable.get(i).destinationAddress == originalsource && myroutingtable.get(i).expTime > currentTimeMillis())
                            {
                                int po = myroutingtable.get(i).nexHop               // Next hop for the OS.
                                def bytes = rreppacket.encode(sourceAddr: originalsource, destAddr: originaldest, destSeqNum: odseqnum, hopCount: hopcountvalue, expirationTime: exp)
                                TxFrameReq tx = new TxFrameReq(to: po, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)
                                sendMessage(tx, CTRL_PACKET)
                                extendRouteLife(po)                         // Extend route life as mentioned on page 21.

                                precursorAddition(originaldest, po)          // Precursor addition, see page 21.
                                precursorAddition(originalsource, msg.from)  // Precursor addition, see page 21.
                                break
                            }
                        }
                    }
                }
            } // RREP

            if (msg.protocol == RM_PROTOCOL)    // ROUTE MAINTENANCE packets: HELLO and RERR.
            {
                def info = rmpacket.decode(msg.data)

                int packettype     = info.type
                int originaldest   = info.destAddr
                int odseqnum       = info.destSeqNum
                int hopcountvalue  = info.hopCount
                long exp           = info.expirationTime

                if (packettype == HELLO)        // HELLO packets.
                {
                    println(myAddr+" RECEIVED A HELLO PACKET.")
                    long life = Math.max(exp, getExpirationTime(originaldest))      // Page 23.
                    routingDetailsUpdate(originaldest, originaldest, odseqnum, ++hopcountvalue, life, VALID_DSN, ACTIVE, VALID_DSN, ACTIVE, HELLO_PACKET, false)
                }
                
                if (packettype == RERR)         // RERR packets.
                {
                    for (int i = 0; i < errorTable.size(); i++)
                    {
                        if (errorTable.get(i).errnode == originaldest && errorTable.get(i).errnum == odseqnum)
                        {
                            return  // Redundancy check for RERR packets.
                        }
                    }

                    PacketError pe = new PacketError(errnode: originaldest, errnum: odseqnum)
                    errorTable.add(pe)          // It's a Unique RERR packet.

                    // The PRECURSORS shall check this Dest and make the corresponding ROUTE ENTRY UNREACHABLE.
                    if (nodePresent(originaldest))
                    {
                        println(myAddr+" SENDING RERR.")
                        routeUnreachable(originaldest)      // page 25: 6.11. -> (iii)
                        // If I have this Dest in my precursor list, I BROADCAST the RERR packet to let other nodes know of the error.
                        int numOfPrecursors = 0
                        for (int i = 0; i < precursorList.size(); i++)
                        {
                            if (precursorList.get(i).finalnode == originaldest)
                            {
                                numOfPrecursors++
                            }
                        }

                        if (numOfPrecursors > 1)    // There are more than 1 precursors for this DEST. BROADCAST the packet.
                        {
                            println(myAddr+" MORE THAN ONE PRECURSORS.")
                            def bytes = rmpacket.encode(type: RERR, destAddr: originaldest, destSeqNum: odseqnum, hopCount: inf, expirationTime: 0)
                            TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: RM_PROTOCOL, data: bytes)
                            sendMessage(tx, CTRL_PACKET)

                            // Wait for this long, then DELETE the route if still INACTIVE.
                            add new WakerBehavior(ALLOWED_HELLO_LOSS*HELLO_INTERVAL, {
                                if (getActiveStatus(originaldest) == INACTIVE || getExpirationTime(originaldest) <= currentTimeMillis())
                                {
                                    removeRouteEntry(originaldest)
                                }
                                })
                        }
                        else if (numOfPrecursors == 1)  // There is only 1 precursor for this DEST. UNICAST the packet.
                        {
                            println(myAddr+" ONLY ONE PRECURSOR")
                            def bytes = rmpacket.encode(type: RERR, destAddr: originaldest, destSeqNum: odseqnum, hopCount: inf, expirationTime: 0)
                            TxFrameReq tx = new TxFrameReq(to: getPrecursor(originaldest), type: Physical.CONTROL, protocol: RM_PROTOCOL, data: bytes)
                            sendMessage(tx, CTRL_PACKET)

                            // Wait for this long, then DELETE the route if still INACTIVE.
                            add new WakerBehavior(ALLOWED_HELLO_LOSS*HELLO_INTERVAL, {
                                if (getActiveStatus(originaldest) == INACTIVE || getExpirationTime(originaldest) <= currentTimeMillis())
                                {
                                    removeRouteEntry(originaldest)
                                }
                                })
                        }
                        else if (numOfPrecursors == 0)  // No precursors left for this DEST.
                        {
                            println(myAddr+" ALL PRECURSORS NOTIFIED.")
                            // Do nothing. All ACTIVE node have been notified of the faulty route.
                        }
                    }
                }
            }
        }
        // This is for DATA packets.
        if (msg instanceof RxFrameNtf && msg.type == Physical.DATA && msg.protocol == DATA_PROTOCOL)
        {
            def info = dataMsg.decode(msg.data)
            int src = info.source
            int des = info.destination

            extendRouteLife(msg.from)   // Update life of all the routes using this PREVIOUS HOP.

            if (myAddr == des)  // 1) I am the final destinaion.
            {
                println(myAddr+" DATA RECEIVED!")
            }
            else                // 2) I am not the final destination for the DATA packet.
            {
                for (int i = 0; i < myroutingtable.size(); i++) // Search for the route. It should be ACTIVE.
                {
                    if (myroutingtable.get(i).destinationAddress == des)
                    {
                        if (myroutingtable.get(i).active == ACTIVE && myroutingtable.get(i).expTime > currentTimeMillis())
                        {
                            int go = myroutingtable.get(i).nexHop               // Next hop for the OD.
                            println(myAddr+" sending DATA to "+des+" via "+go)
                            TxFrameReq tx = new TxFrameReq(to: go, type: Physical.DATA, protocol: DATA_PROTOCOL, data: dataMsg.encode(source: src, destination: des))
                            sendMessage(tx, DATA_PACKET)
                            extendRouteLife(go)         // Update life of the next hop route.
                        }
                        // The route is no longer ACTIVE, broadcast a RERR packet.
                        else if (myroutingtable.get(i).active == INACTIVE || myroutingtable.get(i).expTime < currentTimeMillis())
                        {
                            def bytes = rmpacket.encode(type: RERR, destAddr: des, destSeqNum: ++getDsn(des), hopCount: inf, expirationTime: 0)
                            TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: RM_PROTOCOL, data: bytes)
                            sendMessage(tx, CTRL_PACKET)
                            println(myAddr+" CALLING THE RERR METHOD for "+des)
                            routeErrorPacket(des)    // Sending a RERR packet.
                        }
                        break
                    }
                }
            }
        }
    }

    // Parameters to be received from the Simulation file.
    int controlMsgDuration
    int dataMsgDuration
    int networksize

}
