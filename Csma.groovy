import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.phy.*
import org.arl.unet.mac.*
import org.arl.unet.nodeinfo.*

"""
CSMA MAC protocol
"""

class Csma extends UnetAgent
{
  private AgentID node, phy

  private int myAddr

  private final static int MAX_RETRY_COUNT  = 6

  private final static float BACKOFF_RANDOM = 3.seconds
  private final static float MAX_PROP_DELAY = 1350.milliseconds   // for 1000 m tx range and 1500 mps acoustic speed.

  private final static int MAX_QUEUE_LEN = 16

  Queue<ReservationReq> queue = new ArrayDeque<ReservationReq>()

  private enum State {
    IDLE, WAIT, SENSING, BACKOFF
  }
    
  private FSMBehavior fsm = FSMBuilder.build
  {
    int retryCount = 0
    float backoff = 0

    // IDLE state.
    state(State.IDLE) {
      action {
        if (!queue.isEmpty()) {
          setNextState(State.SENSING)
        }
        block()
      }
    }

    // Wait for random time before sensing the channel.
    state(State.WAIT) {
      onEnter {
        after(rnd(0, BACKOFF_RANDOM)) {
          setNextState(State.SENSING)
        }
      }
    }

    // Backoff state.
    state(State.BACKOFF) {
      onEnter {
        after(backoff.milliseconds) {
          setNextState(State.SENSING)
        }
      }
    }

    // Sense the channel.
    state(State.SENSING) {
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
            // in ms
            backoff = AgentLocalRandom.current().nextExp(targetLoad/msg.duration)*1000
            setNextState(State.BACKOFF)
          }
        }

        else {  // Send Ntf
          ReservationReq msg = queue.poll()
          retryCount = 0
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
      case ReservationAcceptReq:                                  //  respond to other requests defined
      case TxAckReq:                                              //  by the MAC service trivially with
        return new Message(msg, Performative.REFUSE)              //  a REFUSE performative
    }
    return null
  }

  @Override
  void processMessage(Message msg)
  {
    if (msg instanceof RxFrameNtf)
    {
      //
    }
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

  ////// expose parameters that are expected of a MAC service

  final int reservationPayloadSize = 0            // read-only parameters
  final int ackPayloadSize = 0
  final float maxReservationDuration = 65.535 // in seconds

  boolean getChannelBusy() {                      // channel is considered busy if fsm is not IDLE
    return fsm.currentState.name != State.IDLE
  }

  private void sendReservationStatusNtf(ReservationReq msg, ReservationStatus status) {
    send new ReservationStatusNtf(recipient: msg.sender, requestID: msg.msgID, to: msg.to, from: myAddr, status: status)
  }

  // Parameters to be received from the simulation file (late binding).
  int controlMsgDuration
  int dataMsgDuration
  double targetLoad     // load per node.

}