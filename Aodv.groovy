import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.net.*
import org.arl.unet.mac.*
import org.arl.unet.nodeinfo.*

/*  Reference:
 *
 *  C. Perkins, E. Belding-Royer, and S. Das, "Ad-hoc On-Demand Distance Vector (AODV)
 *  Routing," RFC 3561, 2003.
 *
 */

class Aodv extends UnetAgent
{
    private AgentID node, phy, rtr, mac

    private int myAddr

    private int temp = 0                    // Initial Broadcast request ID number (temp) will be zero.
    private int seqn = 0                    // Initial sequential number would be zero.
    private int rreqcount = 0               // Monitors the RREQ rate per second.
    private int rerrcount = 0               // Monitors the RERR rate per second.
    private int firstActiveRouteFlag = 0    // Monitors the FIRST ACTIVE ROUTE instance to initiate HELLO PACKET CHECK.
    private long lastbroadcast = 0          // Last BROADCAST time to facilitate HELLO PACKET check.

    // CONSTANT values ->

    // Types of packet:
    private final static int RREQ               = 0x01
    private final static int RREP               = 0x02
    private final static int RERR               = 0x03
    private final static int HELLO              = 0x04

    // Sequence number status:
    private final static int VALID_DSN          = 1
    private final static int INVALID_DSN        = 0
    private final static int UNKNOWN_DSN        = -1

    // Important parameters:
    private final static int MAX_NUMBER_OF_TXS  = 3
    private final static int ALLOWED_HELLO_LOSS = 2

    private final static int MAX_RREQ_RATE      = 10
    private final static int MAX_RERR_RATE      = 10
    private final static int NET_DIAMETER       = 35

    // Various timeout values:
    private final static long ACTIVE_ROUTE_TIMEOUT  = 3000
    private final static long HELLO_INTERVAL        = 1000
    private final static long NODE_TRAVERSAL_TIME   = 40
    private final static long NET_TRAVERSAL_TIME    = 2*NODE_TRAVERSAL_TIME*NET_DIAMETER

    // Different Protocols used:
    private final static int ROUTING_PROTOCOL = Protocol.ROUTING
    private final static int RM_PROTOCOL      = Protocol.ROUTE_MAINTENANCE
    private final static int DATA_PROTOCOL    = Protocol.DATA

    private final static int HOP_ZERO         = 0
    private final static int HOP_ONE          = 1

    // Active status of a route:
    private final static boolean ACTIVE           = true
    private final static boolean INACTIVE         = false

    private final static boolean NON_HELLO_PACKET = true
    private final static boolean HELLO_PACKET     = false

    private final static boolean RREQ_PKT         = true
    private final static boolean NON_RREQ         = false  

    private final static double inf = Double.POSITIVE_INFINITY  // Infinity

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

    private class PacketError
    {
        private int errnode   // faulty route to this DESTINATION.
        private int errnum    // Destination sequence number.
    }

    ArrayList<Aodv.RoutingInfo> myroutingtable       = new ArrayList<Aodv.RoutingInfo>()        // routing table.
    ArrayList<Aodv.AttemptingHistory> attemptHistory = new ArrayList<Aodv.AttemptingHistory>()  // route discovery attempts.
    ArrayList<Aodv.PacketHistory> myPacketHistory    = new ArrayList<Aodv.PacketHistory>()      // packet history.
    ArrayList<Aodv.Precursor> precursorList          = new ArrayList<Aodv.Precursor>()          // precursor list for destination nodes.
    ArrayList<Aodv.TxReserve> reservationTable       = new ArrayList<Aodv.TxReserve>()          // packet reservation table.
    ArrayList<Aodv.PacketError> errorTable           = new ArrayList<Aodv.PacketError>()        // rerr packet history.

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

    // To refresh the RREQ and RERR count after every second.
    private void refreshCount()
    {
        add new TickerBehavior(1000, {
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
        int dsnvalue = getDsn(destination)    // Some value, or UNKNOWN DSN.

        // Before broadcasting the RREQ, save the details of this packet.
        PacketHistory ph = new PacketHistory(osna: myAddr, ridn: temp, hoco: HOP_ZERO)
        myPacketHistory.add(ph)
        packetHistoryDeletion(myAddr, temp, HOP_ZERO)

        def bytes = rreqpacket.encode(sourceAddr: myAddr, sourceSeqNum: seqn, broadcastId: temp, destAddr: destination, destSeqNum: dsnvalue, hopCount: HOP_ZERO)
        TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)

        sendMessage(tx)

        println(myAddr+" STARTING A ROUTE DISCOVERY.")

        // Page 15: Binary exponential backoff-based timeout for ROUTE DISCOVERY CHECK.
        add new WakerBehavior(routeDiscTimeout(destination)*NET_TRAVERSAL_TIME, {
            routeDiscoveryCheck(destination)
            })
    }

    // Binary exponential backoff for retransmissions.
    private double routeDiscTimeout(int destination)
    {
        for (int i = 0; i < attemptHistory.size(); i++)
        {
            if (attemptHistory.get(i).destinationAddr == destination)
            {
                return Math.pow(2, attemptHistory.get(i).num - 1)
            }
        }
    }

    // Check for Route discovery success.
    private void routeDiscoveryCheck(int dest)
    {
        // Destination node found in the RT with an ACTIVE route.
        if (nodePresent(dest) == true && getActiveStatus(dest) == ACTIVE && getExpirationTime(dest) >= currentTimeMillis())
        {
            println("ROUTE FOUND IN THE RT")
            return
        }

        // Do another Route Discovery.
        else
        {
            println("ROUTE NOT FOUND. DO ANOTHER ROUTE DISCOVERY.")
            def rdp = agentForService(Services.ROUTE_MAINTENANCE)
            rdp << new RouteDiscoveryReq(to: dest, maxHops: 50, count: 1)
        }
    }

    // Sending Reservation Requests for exponential backoff-based carrier sensing.
    private void sendMessage(TxFrameReq txReq)
    {
        if (txReq.type == Physical.CONTROL)     // CTRL packets.
        {
            if (txReq.protocol == ROUTING_PROTOCOL)
            {
                ReservationReq rs = new ReservationReq(to: txReq.to, duration: controlMsgDuration/1000)
                TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
                reservationTable.add(tr)
                mac << rs                           // send ReservationReq.
            }

            if (txReq.protocol == RM_PROTOCOL)
            {
                ReservationReq rs = new ReservationReq(to: txReq.to, duration: 1.62/1000)
                TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
                reservationTable.add(tr)
                mac << rs                           // send ReservationReq.
            }
        }

        if (txReq.type == Physical.DATA)        // DATA packets.
        {
            ReservationReq rs = new ReservationReq(to: txReq.to, duration: dataMsgDuration/1000)
            TxReserve tr = new TxReserve(txreq: txReq, resreq: rs)
            reservationTable.add(tr)
            mac << rs                           // send ReservationReq.
        }
    }

    //  As mentioned on Page 22, 6.9., after every HELLO_INTERVAL, 
    //  I check the LAST BROADCAST of a node if there is a single ACTIVE route present in the RT.
    private void localConnectivityManagement()
    {
        add new TickerBehavior(HELLO_INTERVAL, {

            int checkActive = 0     // After every HELLO_INTERVAL, this should become 1 if there's a single ACTIVE ROUTE present.

            for (int i = 0; i < myroutingtable.size(); i++)
            {
                // If there is any ACTIVE route, I check whether I BROADCASTED any packet in the last HELLO_INTERVAL.
                if (myroutingtable.get(i).active == ACTIVE && myroutingtable.get(i).expTime >= currentTimeMillis())
                {
                    checkActive = 1

                    add new WakerBehavior(rnd(0, HELLO_INTERVAL), 
                    {
                        if (lastbroadcast < currentTimeMillis() - HELLO_INTERVAL)   // Last broadcast happened before HELLO_INTERVAL.
                        {
                            long expiry = currentTimeMillis() + ALLOWED_HELLO_LOSS*HELLO_INTERVAL   // See Page 22.

                            TxFrameReq tx = new TxFrameReq(         // Preparing the HELLO packet.
                                to:         Address.BROADCAST,
                                type:       Physical.CONTROL,
                                protocol:   RM_PROTOCOL,
                                data:       rmpacket.encode(type: HELLO, destAddr: myAddr, destSeqNum: seqn, hopCount: HOP_ZERO, expirationTime: expiry)
                                )

                            sendMessage(tx)
                            println(myAddr+" HELLO at "+currentTimeMillis())
                        }
                        })

                    break
                }
            }

            if (checkActive == 0)   // There are no ACTIVE ROUTES in this node's RT.
            {
                firstActiveRouteFlag = 0
                stop()
                return
            }
            })
    }

    // To look after HELLO PACKET-generated routes regularly. See page 22, 6.9.
    private void helloRoute(int node)
    {
        add new TickerBehavior(ALLOWED_HELLO_LOSS*HELLO_INTERVAL,
        {
            if (getActiveStatus(node) == INACTIVE || getExpirationTime(node) < currentTimeMillis())
            {
                routeErrorPacket(node)  // Do a ROUTE ERROR analysis.
            }
            })
    }

    // Sending RERR to the PRECURSORS (if any) of the affected destination.
    private void routeErrorPacket(int target)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == target)
            {
                myroutingtable.get(i).expTime = 0           // EXPIRATION time as zero.
                myroutingtable.get(i).active = INACTIVE     // DEACTIVATING the route.
                int so = ++myroutingtable.get(i).dsn        // Incrementing the DSN.

                println(myAddr+" SENDING a RERR packet for "+target)

                // Send a RERR packet if there are any PRECURSORS for TARGET.

                if (getPrecursorCount(target) == 0)
                {
                    // No need for RERR unicast or broadcast.
                }

                // There is only ONE PRECURSOR.
                if (getPrecursorCount(target) == 1)
                {
                    PacketError pe = new PacketError(errnode: target, errnum: so)
                    errorTable.add(pe)      // Add this RERR packet in Error Table history.

                    // Preparing the RERR packet for UNICAST.
                    def bytes = rmpacket.encode(type: RERR, destAddr: target, destSeqNum: so, hopCount: inf, expirationTime: 0)
                    TxFrameReq tx = new TxFrameReq(to: getPrecursor(target), type: Physical.CONTROL, protocol: RM_PROTOCOL, data: bytes)

                    sendMessage(tx)
                }

                // There are MORE THAN ONE PRECURSORS.
                if (getPrecursorCount(target) > 1)
                {
                    PacketError pe = new PacketError(errnode: target, errnum: so)
                    errorTable.add(pe)      // Add this RERR packet in Error Table history.

                    // Preparing the RERR packet for BROADCAST.
                    def bytes = rmpacket.encode(type: RERR, destAddr: target, destSeqNum: so, hopCount: inf, expirationTime: 0)
                    TxFrameReq tx = new TxFrameReq(to: Address.BROADCAST, type: Physical.CONTROL, protocol: RM_PROTOCOL, data: bytes)

                    sendMessage(tx)
                }

                // Wait for this long, then DELETE the route if still INACTIVE.
                add new WakerBehavior(ALLOWED_HELLO_LOSS*HELLO_INTERVAL, 
                {
                    if (getActiveStatus(target) == INACTIVE || getExpirationTime(target) < currentTimeMillis())
                    {
                        removeRouteEntry(target)
                    }

                    })

                break
            }
        }
    }

    // Remove a route entry along with its precursor list.
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

    // When a route is used to transmit DATA/RREP packets, its life is extended by max of current expiration buffer and ACTIVE_ROUTE_TIMEOUT.
    private void extendRouteLife(int node)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == node)
            {
                println(myAddr+" EXTENDED ROUTE LIFE of "+node)
                myroutingtable.get(i).active  = ACTIVE
                myroutingtable.get(i).expTime = Math.max(getExpirationTime(node), currentTimeMillis() + ACTIVE_ROUTE_TIMEOUT)   // Page 21.
                break
            }
        }
    }

    /*  If the OS is already there in the RT, update its details in case
    *   the seq number of packet < the sequence number in the RT, or
    *   the seq numbers of both the packet and the RT are equal, but the packet has a smaller hop count than that in the RT, or
    *   the seq number in the RT is UNKNOWN.
    */
    private void routingDetailsUpdate(  int origin, int from, int seqnum, int hcv, long life, int statusone, boolean statustwo,
                                        int statusthree, boolean statusfour, boolean nonhello, boolean rreq)
    {
        if (origin == from)         // 1) If the OS directly sent me this message.
        {
            int flag = 0

            for (int i = 0; i < myroutingtable.size(); i++)
            {
                if (myroutingtable.get(i).destinationAddress == origin)
                {
                    flag = 1    // The node is already there in the RT.

                    if (rreq)   // RREQ packet. For these conditions, see page 16: 6.5.
                    {
                        myroutingtable.get(i).validdsn = statusone  // always VALID.

                        if (myroutingtable.get(i).dsn < seqnum)
                        {
                            myroutingtable.get(i).dsn = seqnum
                        }

                        myroutingtable.get(i).nexHop  = from
                        myroutingtable.get(i).numHops = hcv
                        myroutingtable.get(i).expTime = life
                        myroutingtable.get(i).active  = statustwo
                    }

                    // RREP and HELLO packets.
                    if (!rreq && (myroutingtable.get(i).dsn < seqnum || myroutingtable.get(i).validdsn == INVALID_DSN ||
                        (myroutingtable.get(i).dsn == seqnum && (hcv < myroutingtable.get(i).numHops || myroutingtable.get(i).active == INACTIVE))))
                    {   
                        // For these conditions, see page 20: 6.7.

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

                if (nonhello && rreq)       // To purge any unwanted route entries created during a Route Discovery.
                {                           // Only RREQ-generated routes.
                    routeValidityCheck(life - currentTimeMillis(), origin)
                }

            }

            if (nonhello && statustwo && firstActiveRouteFlag == 0)     // The Non HELLO PACKET-generated ROUTE is ACTIVE.
            {                                                           // HELLO Packet Check has not yet been initiated.
                firstActiveRouteFlag = 1

                localConnectivityManagement()   // Start HELLO PACKET check as I have an ACTIVE NEIGHBOUR node.
            }

            if (!nonhello)                      // It's a HELLO packet-generated route. Monitor its status regularly.
            {
                helloRoute(origin)              // Page 22, 6.9.
            }

        }

        else    // 2) The OS did not send this message directly, it came from an intermediate node.
        {
            int flag = 0

            for (int i = 0; i < myroutingtable.size(); i++)
            {
                if (myroutingtable.get(i).destinationAddress == origin)
                {
                    flag = 1    // The node is already there in the RT.

                    if (rreq)   // RREQ packet. For these conditions, see page 16: 6.5.
                    {
                        myroutingtable.get(i).validdsn = statusone  // always VALID.

                        if (myroutingtable.get(i).dsn < seqnum)     // Updated if only the seqnum > RT seqn.
                        {
                            myroutingtable.get(i).dsn = seqnum
                        }

                        myroutingtable.get(i).nexHop  = from
                        myroutingtable.get(i).numHops = hcv
                        myroutingtable.get(i).expTime = life
                        myroutingtable.get(i).active  = statustwo
                    }

                    // RREP packets.
                    if (!rreq && (myroutingtable.get(i).dsn < seqnum || myroutingtable.get(i).validdsn == INVALID_DSN ||
                        (myroutingtable.get(i).dsn == seqnum && (hcv < myroutingtable.get(i).numHops || myroutingtable.get(i).active == INACTIVE))))
                    {   
                        // For these conditions, see page 20: 6.7.

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

                if (rreq)       // To purge any unwanted route entries created during a Route Discovery.
                {
                    routeValidityCheck(life - currentTimeMillis(), origin)
                }

            }

            if (statustwo)                  // The Non HELLO PACKET-generated ROUTE is ACTIVE.
            {
                activeStatusCheck(origin)   // The route is ACTIVE, keep a regular check on its ACTIVE STATUS.
            }

            int verification = 0

            for (int i = 0; i < myroutingtable.size(); i++)
            {
                if (myroutingtable.get(i).destinationAddress == from)   // The next hop towards the origin is already there in the RT.
                {
                    verification = 1        // The node is already there in the RT.

                    if (getDsnStatus(from) == INVALID_DSN) // The DSN status shall be changed only when it is INVALID.
                    {
                        myroutingtable.get(i).validdsn = statusthree   
                    }

                    myroutingtable.get(i).dsn     = getDsn(from)    // Either some value or UNKNOWN.
                    myroutingtable.get(i).nexHop  = from
                    myroutingtable.get(i).numHops = HOP_ONE

                    // If this node was already there, it's TIMEOUT should be the max of current expiration buffer and life. 
                    myroutingtable.get(i).expTime = Math.max(getExpirationTime(from), life)
                    
                    myroutingtable.get(i).active  = statusfour

                    break
                }
            }

            // This neighbour node was never there in the RT.
            if (verification == 0)
            {
                RoutingInfo ri = new RoutingInfo(
                    destinationAddress: from,
                    validdsn:           statusthree,
                    dsn:                UNKNOWN_DSN,    // Keep the DSN of this guy as UNKNOWN for now.
                    nexHop:             from,
                    numHops:            HOP_ONE,
                    expTime:            life,
                    active:             statusfour
                    )

                myroutingtable.add(ri)

                if (rreq)           // To purge any unwanted route entries created during a Route Discovery.
                {
                    routeValidityCheck(life - currentTimeMillis(), from)
                }

            }

            if (statusfour && firstActiveRouteFlag == 0)    // The Non HELLO PACKET-generated ROUTE is ACTIVE.
            {                                               // HELLO Packet check has not yet been initiated.
                firstActiveRouteFlag = 1

                localConnectivityManagement()               // Start HELLO PACKET check, as I have an ACTIVE NEIGHBOUR node.
            }
        }
    }

    // To purge any unwanted REVERSE ROUTE entries added during Route discovery.
    private void routeValidityCheck(long timegap, int node)
    {
        add new WakerBehavior(timegap,
        {
            for (int i = 0; i < myroutingtable.size(); i++)
            {
                if (myroutingtable.get(i).destinationAddress == node)
                {
                    if (myroutingtable.get(i).expTime < currentTimeMillis() || myroutingtable.get(i).active == INACTIVE)
                    {
                        myroutingtable.remove(i)
                        println(myAddr+" REMOVED UNWANTED REVERSE ENTRY "+node)
                        return
                    }
                }
            }
            })
    }

    // After every ACTIVE_ROUTE_TIMEOUT seconds, the ACTIVE STATUS of the route is CHECKED.
    // If the ROUTE is found as INACTIVE, a RERR packet is prepared.
    private void activeStatusCheck(int node)
    {
        add new TickerBehavior(ACTIVE_ROUTE_TIMEOUT,
        {
            for (int i = 0; i < myroutingtable.size(); i++)
            {
                if (myroutingtable.get(i).destinationAddress == node)
                {
                    if (myroutingtable.get(i).expTime < currentTimeMillis() || myroutingtable.get(i).active == INACTIVE)
                    {
                        routeErrorPacket(node)      // Notify the other PRECURSORS about this INACTIVE route.
                        stop()
                        return
                    }

                    break
                }
            }
            })
    }

    // Delete PH after twice of the NET_TRAVERSAL_TIME period.
    private void packetHistoryDeletion(int sender, int requestId, int hopcount)
    {
        add new WakerBehavior(2*NET_TRAVERSAL_TIME,
        {
            for (int i = 0; i < myPacketHistory.size(); i++)
            {
                if (myPacketHistory.get(i).osna == sender && myPacketHistory.get(i).ridn == requestId && myPacketHistory.get(i).hoco == hopcount)
                {
                    myPacketHistory.remove(i)
                    return
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

    private int getNextHop(int destination)
    {
        for (int i = 0; i < myroutingtable.size(); i++)
        {
            if (myroutingtable.get(i).destinationAddress == destination)
            {
                return myroutingtable.get(i).nexHop
            }
        }
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

    private int getPrecursorCount(int destination)
    {
        int count = 0 
        for (int i = 0; i < precursorList.size(); i++)
        {
            if (precursorList.get(i).finalnode == destination)
            {
                count++
            }
        }

        return count
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

        // Either this destination was never searched before or this is the first ever search by this node.
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
                if (nodePresent(fd) && getActiveStatus(fd) == ACTIVE && getExpirationTime(fd) >= currentTimeMillis())
                {
                    int ko = getNextHop(fd)             // Destination found in the RT and the route is active.
                    int jo = getHopCount(fd)            // Number of hops.
                    rtr << new RouteDiscoveryNtf(to: fd, nextHop: ko, hops: jo, reliability: true)  // Sending Route discovery notification.
                    return new Message(msg, Performative.AGREE)
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
                        phy << reservationTable.get(i).txreq    // Send the TxFrameReq.
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

        // Routing packets: RREQ and RREP.
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
                    myPacketHistory.add(ph)         // Add the packet's details.
                    packetHistoryDeletion(originalsource, requestIDNo, hopcountvalue)

                    if (originaldest == myAddr)     // 1) I am the Final Destination.
                    {
                        seqn = Math.max(seqn, odseqnum)                                 // Sequence number update.

                        long clock = Math.max(getExpirationTime(originalsource), currentTimeMillis() + 2*ACTIVE_ROUTE_TIMEOUT)  // New expiry time for this route.

                        routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, ACTIVE, INVALID_DSN, ACTIVE, NON_HELLO_PACKET, NON_RREQ)
                        
                        println(myAddr+" is the FD. Sending an RREP back. "+originalsource+' exptime: '+clock)
                        
                        long expiry = currentTimeMillis() + 2*ACTIVE_ROUTE_TIMEOUT      // Page 18, 6.6.1.

                        TxFrameReq tx = new TxFrameReq(                         // Preparing the RREP packet.
                            to:         msg.from,
                            type:       Physical.CONTROL,
                            protocol:   ROUTING_PROTOCOL,
                            data:       rreppacket.encode(  sourceAddr: originalsource, destAddr: originaldest,
                                                            destSeqNum: seqn, hopCount: HOP_ZERO, expirationTime: expiry)
                            )

                        sendMessage(tx)
                    }

                    else    // 2) I am not the Destination. Simply re-broadcast the RREQ packet.
                    {
                        long clock = Math.max(getExpirationTime(originalsource), currentTimeMillis() + 2*NET_TRAVERSAL_TIME - 2*hopcountvalue*NODE_TRAVERSAL_TIME)
                        // Page 17
                        routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, INACTIVE, INVALID_DSN, INACTIVE, NON_HELLO_PACKET, RREQ_PKT)

                        int sop = Math.max(getDsn(originaldest), odseqnum)      // Sequence number to be transmitted. Page 17: Lastly,...
                        
                        println(myAddr+" is not the FD. Re-broadcasting the RREQ. "+originalsource+' exptime: '+clock)
                        
                        TxFrameReq tx = new TxFrameReq(                         // Re-broadcast the RREQ packet.
                            to:         Address.BROADCAST,
                            type:       Physical.CONTROL,
                            protocol:   ROUTING_PROTOCOL,
                            data:       rreqpacket.encode(  sourceAddr: originalsource, sourceSeqNum: osseqnum, broadcastId: requestIDNo,
                                                            destAddr: originaldest, destSeqNum: sop, hopCount: hopcountvalue)
                            )

                        sendMessage(tx)
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

                            // If the hop count in the packet < that in the RT, accept it.
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

                    if (cool == 0)          // The packet was not found in the PH table, add its details.
                    {
                        PacketHistory ph = new PacketHistory(osna: originalsource, ridn: requestIDNo, hoco: hopcountvalue)
                        myPacketHistory.add(ph)
                        packetHistoryDeletion(originalsource, requestIDNo, hopcountvalue)
                    }

                    // 1) I am the Destination.
                    if (myAddr == originaldest)
                    {
                        seqn = Math.max(seqn, odseqnum)                                 // Sequence number update.

                        long clock = Math.max(getExpirationTime(originalsource), currentTimeMillis() + 2*ACTIVE_ROUTE_TIMEOUT)  // New expiry time for this route.

                        routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, ACTIVE, INVALID_DSN, ACTIVE, NON_HELLO_PACKET, NON_RREQ)

                        long expiry = currentTimeMillis() + 2*ACTIVE_ROUTE_TIMEOUT      // Page 18, 6.6.1.

                        TxFrameReq tx = new TxFrameReq(                                 // Preparing the RREP packet.
                            to:         msg.from,
                            type:       Physical.CONTROL,
                            protocol:   ROUTING_PROTOCOL,
                            data:       rreppacket.encode(  sourceAddr: originalsource, destAddr: originaldest,
                                                            destSeqNum: seqn, hopCount: HOP_ZERO, expirationTime: expiry)
                            )

                        sendMessage(tx)
                    }

                    // 2) I am not the final destination.
                    else
                    {
                        // 1) An ACTIVE route is there for the DESTINATION.
                        if (nodePresent(originaldest) && getActiveStatus(originaldest) == ACTIVE && getExpirationTime(originaldest) >= currentTimeMillis())
                        {
                            // 1.1) If the route is Current. Send an RREP back to the OS.
                            if (getDsn(originaldest) >= odseqnum)
                            {
                                long clock = Math.max(getExpirationTime(originalsource), currentTimeMillis() + 2*ACTIVE_ROUTE_TIMEOUT)  // New expiry time for this route.

                                routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, ACTIVE, INVALID_DSN, ACTIVE, NON_HELLO_PACKET, NON_RREQ)

                                int nextnode = getNextHop(originaldest)         // Next hop for the destination.

                                precursorAddition(originaldest, msg.from)       // Precursor list updated for forward route entry.
                                precursorAddition(originalsource, nextnode)     // Precursor list updated for reverse route entry.                                 

                                int lo = getDsn(originaldest)                   // Sequence number of the destination.
                                int go = getHopCount(originaldest)              // The hop count value for the OD from this node.
                                long expiry = getExpirationTime(originaldest)   // Expiry time for the RREP, same as that of the route for DESTINATION. 
                                                                                        // See page 19.
                                TxFrameReq tx = new TxFrameReq(                     // Preparing the RREP packet.
                                    to:         msg.from,
                                    type:       Physical.CONTROL,
                                    protocol:   ROUTING_PROTOCOL,
                                    data:       rreppacket.encode(  sourceAddr: originalsource, destAddr: originaldest,
                                                                    destSeqNum: lo, hopCount: go, expirationTime: expiry)
                                    )

                                sendMessage(tx)

                                // Expiry time for G-RREP would be the same as that of ROUTE LIFE for the OS known by this intermediate node.
                                TxFrameReq gtx = new TxFrameReq(                 // Preparing the Gratuitous RREP packet.
                                    to:         nextnode,
                                    type:       Physical.CONTROL,
                                    protocol:   ROUTING_PROTOCOL,
                                    data:       rreppacket.encode(  sourceAddr: originaldest, destAddr: originalsource,
                                                                    destSeqNum: osseqnum, hopCount: hopcountvalue, expirationTime: clock)
                                    )

                                sendMessage(gtx)
                            }

                            // 1.2) The route is not current. Re-broadcast the RREQ.
                            if (getDsn(originaldest) < odseqnum)
                            {
                                long clock = Math.max(getExpirationTime(originalsource), currentTimeMillis() + 2*NET_TRAVERSAL_TIME - 2*hopcountvalue*NODE_TRAVERSAL_TIME)
                                // Page 17.
                                routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, INACTIVE, INVALID_DSN, INACTIVE, NON_HELLO_PACKET, RREQ_PKT)

                                int sop = Math.max(getDsn(originaldest), odseqnum)      // Sequence number to be transmitted. Page 17: Lastly,...

                                TxFrameReq tx = new TxFrameReq(                         // Preparing the RREQ packet for re-broadcast.
                                    to:         Address.BROADCAST,
                                    type:       Physical.CONTROL,
                                    protocol:   ROUTING_PROTOCOL,
                                    data:       rreqpacket.encode(  sourceAddr: originalsource, sourceSeqNum: osseqnum, broadcastId: requestIDNo,
                                                                    destAddr: originaldest, destSeqNum: sop, hopCount: hopcountvalue)
                                    )

                                sendMessage(tx)
                            }
                        }

                        // 2) No ACTIVE ROUTE for the DESTINATION in the RT. Re-broadcast the RREQ.
                        else
                        {
                            long clock = Math.max(getExpirationTime(originalsource), currentTimeMillis() + 2*NET_TRAVERSAL_TIME - 2*hopcountvalue*NODE_TRAVERSAL_TIME)
                            // Page 17.
                            routingDetailsUpdate(originalsource, msg.from, osseqnum, hopcountvalue, clock, VALID_DSN, INACTIVE, INVALID_DSN, INACTIVE, NON_HELLO_PACKET, RREQ_PKT)

                            int sop = Math.max(getDsn(originaldest), odseqnum)          // Sequence number to be transmitted. Page 17: Lastly,...

                            TxFrameReq tx = new TxFrameReq(                             // Preparing the RREQ packet.                           
                                to:         Address.BROADCAST,
                                type:       Physical.CONTROL,
                                protocol:   ROUTING_PROTOCOL,
                                data:       rreqpacket.encode(  sourceAddr: originalsource, sourceSeqNum: osseqnum, broadcastId: requestIDNo,
                                                                destAddr: originaldest, destSeqNum: sop, hopCount: hopcountvalue)
                                )

                            sendMessage(tx)
                        }
                        
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

                if (exp >= currentTimeMillis() && getDsn(originaldest) <= odseqnum && ++hopcountvalue <= NET_DIAMETER)
                {
                    // Life of the route would be the same as that of the RREP packet (see page 21), but I compare
                    // it with the already present value (if any) to ensure that the greater one is allocated.

                    long clock = Math.max(getExpirationTime(originaldest), exp)

                    routingDetailsUpdate(originaldest, msg.from, odseqnum, hopcountvalue, clock, VALID_DSN, ACTIVE, INVALID_DSN, ACTIVE, NON_HELLO_PACKET, NON_RREQ)

                    if (myAddr == originalsource)   // 1) I am the OS.
                    {
                        println(myAddr+" ROUTE DISCOVERY OVER FOR "+originaldest)
                        rtr << new RouteDiscoveryNtf(to: originaldest, nextHop: msg.from, hops: hopcountvalue, reliability: true) // Send a RouteDiscoveryNtf.
                    }

                    else                            // 2) I am not the OS.
                    {
                        println(myAddr+" not the OS. Sending RREP back to the OS.")

                        if (nodePresent(originalsource) && getExpirationTime(originalsource) >= currentTimeMillis())
                        {
                            int po = getNextHop(originalsource)     // Next hop for the OS.

                            def bytes = rreppacket.encode(sourceAddr: originalsource, destAddr: originaldest, destSeqNum: odseqnum, hopCount: hopcountvalue, expirationTime: exp)
                            TxFrameReq tx = new TxFrameReq(to: po, type: Physical.CONTROL, protocol: ROUTING_PROTOCOL, data: bytes)

                            sendMessage(tx)

                            extendRouteLife(originalsource)         // Extend route life for the ORIGINAL SOURCE.
                            extendRouteLife(po)                     // Extend route life for the NEXT HOP as mentioned on page 21.

                            precursorAddition(originaldest, po)         // Precursor addition for forward route entry, see page 21.
                            precursorAddition(originalsource, msg.from) // Precursor addition for reverse route entry, see page 21.
                        }
                    }
                }
            } // RREP

            // ROUTE MAINTENANCE packets: HELLO and RERR.
            if (msg.protocol == RM_PROTOCOL)
            {
                def info = rmpacket.decode(msg.data)

                int packettype     = info.type
                int originaldest   = info.destAddr
                int odseqnum       = info.destSeqNum
                int hopcountvalue  = info.hopCount
                long exp           = info.expirationTime

                // HELLO packets.
                if (packettype == HELLO)
                {
                    println(myAddr+" RECEIVED A HELLO PACKET from "+msg.from)

                    long life = Math.max(exp, getExpirationTime(originaldest))      // Keep the route life as Maximum. Page 23.

                    routingDetailsUpdate(originaldest, originaldest, odseqnum, ++hopcountvalue, life, VALID_DSN, ACTIVE, VALID_DSN, ACTIVE, HELLO_PACKET, NON_RREQ)
                }
                
                // RERR packets.
                if (packettype == RERR)
                {
                    for (int i = 0; i < errorTable.size(); i++)
                    {
                        if (errorTable.get(i).errnode == originaldest && errorTable.get(i).errnum == odseqnum)
                        {
                            return  // Redundancy check for RERR packets.
                        }
                    }

                    PacketError pe = new PacketError(errnode: originaldest, errnum: odseqnum)
                    errorTable.add(pe)              // Add this RERR Packet's details.

                    routeErrorPacket(originaldest)  // Do a ROUTE ERROR analysis.
                }
            }
        }

        // DATA packets.
        if (msg instanceof RxFrameNtf && msg.type == Physical.DATA && msg.protocol == DATA_PROTOCOL)
        {
            def info = dataMsg.decode(msg.data)
            int src = info.source
            int des = info.destination

            extendRouteLife(src)        // Update route life for the ORIGINAL SOURCE of the packet.
            extendRouteLife(msg.from)   // Update route life for the PREVIOUS HOP of the packet.

            if (myAddr == des)  // 1) I am the FINAL DESTINATION.
            {
                println(myAddr+" DATA RECEIVED!")
            }

            else                // 2) I am not the FINAL DESTINATION for the DATA packet.
            {
                if (nodePresent(des))
                {
                    // The route is ACTIVE.
                    if (getActiveStatus(des) == ACTIVE && getExpirationTime(des) >= currentTimeMillis())
                    {
                        int go = getNextHop(des)    // Next hop for the OD.
                        println(myAddr+" sending DATA to "+des+" via "+go)
                        TxFrameReq tx = new TxFrameReq(to: go, type: Physical.DATA, protocol: DATA_PROTOCOL, data: dataMsg.encode(source: src, destination: des))

                        sendMessage(tx)

                        extendRouteLife(des)        // Update life of this FINAL DESTINATION.
                        extendRouteLife(go)         // Update life of this NEXT HOP.
                    }

                    // The route is no longer ACTIVE.
                    else
                    {
                        routeErrorPacket(des)       //  Do a ROUTE ERROR analysis.
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
