import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.net.*
import org.arl.unet.PDU
import org.arl.unet.nodeinfo.NodeInfo

class LoadGenerator extends UnetAgent {

  private List<Integer> destNodes                     // list of possible destination nodes
  private float load                                  // normalized load to generate

  private AgentID phy, rdp, node
  private int myAddr

  LoadGenerator(List<Integer> destNodes, float load) {
    this.destNodes = destNodes                        
    this.load = load                                  
  }

  def dataMsg = PDU.withFormat
  {
    uint16('source')
    uint16('destination')
  }

  @Override
  void setup()
  {
    register Services.ROUTING
  }

  @Override
  void startup() {
    phy = agentForService Services.PHYSICAL
    subscribe phy

    node = agentForService Services.NODE_INFO
    myAddr = node.Address
    
    rdp = agentForService Services.ROUTE_MAINTENANCE
    float dataPktDuration = get(phy, Physical.DATA, PhysicalChannelParam.frameDuration)
    // compute average packet arrival rate
    float rate = load/dataPktDuration
    // create Poisson arrivals at given rate
    add new PoissonBehavior(1000/rate, {
      rdp << new RouteDiscoveryReq(to: rnditem(destNodes), maxHops: 35, count: 1)
      })
  }

  private void rxDisable()
  {
    //DisableReceiver
    ParameterReq req = new ParameterReq(agentForService(Services.PHYSICAL))
    req.get(PhysicalParam.rxEnable)
    ParameterRsp rsp = (ParameterRsp) request(req, 1000)            
    rsp.set(PhysicalParam.rxEnable,false) 
  }

  private void rxEnable()
  {
    //EnableReceiver
    ParameterReq req = new ParameterReq(agentForService(Services.PHYSICAL))
    req.get(PhysicalParam.rxEnable)
    ParameterRsp rsp = (ParameterRsp)request(req, 1000)         
    rsp.set(PhysicalParam.rxEnable,true) 
  }

  @Override
  void processMessage(Message msg) {
    if (msg instanceof RouteDiscoveryNtf && msg.reliability == true) {
      phy << new ClearReq()   // clear any on-going transmission.
      rxDisable()
      phy << new TxFrameReq(to: msg.nextHop, type: Physical.DATA, protocol: Protocol.DATA, data: dataMsg.encode([source: myAddr, destination: msg.to]))
      add new WakerBehavior(214, {rxEnable()})    // Data packet duration of a 64-byte packet at 2400 bps: 214 ms 
    }
  }

}
