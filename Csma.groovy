import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.mac.*
import org.arl.unet.nodeinfo.NodeInfo

class Csma extends UnetAgent
{
  private AgentID node, phy

  private int myAddr

  private final static int MAX_RETRY_COUNT  = 10
  private final static float BACKOFF_RANDOM = 20.milliseconds
  private final static int MAX_QUEUE_LEN    = 50

  Queue<ReservationReq> queue = new ArrayDeque<ReservationReq>()

  private enum State {
    IDLE, WAIT, SENSING, BACKOFF
  }

  private enum Event {
    RX_CTRL, RX_ROUTE_MAINTENANCE, RX_DATA, SNOOP_CTRL, SNOOP_ROUTE_MAINTENANCE, SNOOP_DATA
  }
    
  private FSMBehavior fsm = FSMBuilder.build
  {
    int retryCount = 0
    float backoff = 0

    state(State.IDLE) {   // State
      action {
        if (!queue.isEmpty()) {
          setNextState(State.SENSING)
        }
        block()
      }
    }

    state(State.WAIT) {   // State
      onEnter {
        after(rnd(0, BACKOFF_RANDOM)) {
          setNextState(State.SENSING)
        }
      }
    }

    state(State.BACKOFF) {  // State
      onEnter {
        after(backoff.milliseconds) {
          setNextState(State.SENSING)
        }
      }

      onEvent(Event.RX_CTRL) {
        backoff = controlMsgDuration
        reenterState()
      }

      onEvent(Event.RX_ROUTE_MAINTENANCE) {
        backoff = 1.62  // duration for RM packets.
        reenterState()
      }

      onEvent(Event.RX_DATA) {
        backoff = dataMsgDuration
        reenterState()
      }

      onEvent(Event.SNOOP_CTRL) {
        backoff = controlMsgDuration
        reenterState()
      }

      onEvent(Event.SNOOP_CTRL) {
        backoff = 1.62  // duration for RM packets.
        reenterState()
      }

      onEvent(Event.SNOOP_DATA) {
        backoff = dataMsgDuration
        reenterState()
      }
    }

    state(State.SENSING) {  // State
      onEnter {
        if (phy.busy) { // This would only be for receiving any packets.
          if (retryCount == MAX_RETRY_COUNT) {
            sendReservationStatusNtf(queue.poll(), ReservationStatus.FAILURE)
            retryCount = 0
            setNextState(State.IDLE)
          }
          else if (retryCount < MAX_RETRY_COUNT) {
            retryCount++
            Message msg = queue.peek()
            backoff = getNumberofSlots(retryCount)*msg.duration*1000  // Duration of backoff in milliseconds.
            setNextState(State.BACKOFF)
          }
        }
        else {  // Send Ntf
          ReservationReq msg = queue.poll()
          retryCount = 0
          phy << new ClearReq()
          rxDisable()
          sendReservationStatusNtf(msg, ReservationStatus.START)
          after(msg.duration) {
            sendReservationStatusNtf(msg, ReservationStatus.END)
            rxEnable()
            setNextState(State.IDLE)
          }
        }
      }
    }
  }
  // Exponential Backoff slots.
  private int getNumberofSlots(int slots)
  {
    int product = 1
    while(slots > 0) {
      product = product*2
      slots--
    }
    return AgentLocalRandom.current().nextInt(--product)
  }

  @Override
  void setup()
  {
    register Services.MAC
  }

  @Override
  void startup()
  {
    phy = agentForService(Services.PHYSICAL)
    subscribe phy
    subscribe(topic(phy, Physical.SNOOP))

    node = agentForService(Services.NODE_INFO)
    myAddr = node.Address
    add(fsm)
  }

  @Override
  Message processRequest(Message msg)
  {
    switch (msg)
    {
      case ReservationReq:
      if (msg.duration <= 0) return new Message(msg, Performative.REFUSE)
      if (queue.size() >= MAX_QUEUE_LEN || msg.duration > maxReservationDuration) return new Message(msg, Performative.REFUSE)
      queue.add(msg)            // add this ReservationReq in the queue
      if (!getChannelBusy())    // channel is in IDLE state
      {
        fsm.restart()           // restart fsm
      }
      return new ReservationRsp(msg)
      case ReservationCancelReq:
      case ReservationAcceptReq:                                  // respond to other requests defined
      case TxAckReq:                                              //  by the MAC service trivially with
        return new Message(msg, Performative.REFUSE)            //  a REFUSE performative
    }
    return null
  }

  @Override
  void processMessage(Message msg)
  {
    if (msg instanceof RxFrameNtf)
    {
      if (msg.type == Physical.CONTROL)
      {
        if (msg.protocol == Protocol.ROUTING)
        {
          fsm.trigger(msg.to == myAddr ? Event.RX_CTRL : Event.SNOOP_CTRL)
        }
        if (msg.protocol == Protocol.ROUTE_MAINTENANCE)
        {
          fsm.trigger(msg.to == myAddr ? Event.RX_ROUTE_MAINTENANCE : Event.SNOOP_ROUTE_MAINTENANCE)
        }
      }
      if (msg.type == Physical.DATA)
      {
        fsm.trigger(msg.to == myAddr ? Event.RX_DATA : Event.SNOOP_DATA)
      }
    }
  }

  private void rxDisable()
  {
    //  Disable Receiver.
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

  final float maxReservationDuration = 0.01 // in seconds

  boolean getChannelBusy() {                      // channel is considered busy if fsm is not IDLE
    return fsm.currentState.name != State.IDLE
  }

  private void sendReservationStatusNtf(ReservationReq msg, ReservationStatus status) {
    send new ReservationStatusNtf(recipient: msg.sender, requestID: msg.msgID, to: msg.to, from: myAddr, status: status)
  }
  
  // Parameters to be received from the simulation file.
  int controlMsgDuration
  int dataMsgDuration
  
}
