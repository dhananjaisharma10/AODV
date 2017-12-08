//! Simulation: AODV Routing Protocol

import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.sim.*
import org.arl.unet.sim.channels.*
import static org.arl.unet.Services.*

println '''
AODV Protocol Simulation
========================
'''

///////////////////////////////////////////////////////////////////////////////
// modem and channel model parameters

modem.dataRate         = [2400, 2400].bps
modem.frameLength      = [24, 64].bytes
modem.preambleDuration = 0
modem.txDelay          = 0
modem.clockOffset      = 0.s
modem.headerLength     = 0.s

channel.model              = ProtocolChannelModel
channel.soundSpeed         = 1500.mps
channel.communicationRange = 2000.m                     // Maximum TRANSMISSION Range
channel.interferenceRange  = 2000.m
channel.detectionRange     = 2000.m

///////////////////////////////////////////////////////////////////////////////
// simulation settings

def nodes = 1..5
def T = 15.minutes          // simulator horizon

def dimension = Math.sqrt(2500000*nodes.size())   // planar density of 1 node per 2.5 sq km

///////////////////////////////////////////////////////////////////////////////
// generate random network geometry
def nodeLocation = [:]
nodes.each { myAddr ->
  nodeLocation[myAddr] = [rnd(0.m, dimension.m), rnd(0.m, dimension.m), -15.m]
}

// compute average distance between nodes for display
def sum = 0
def n = 0
def propagationDelay = new Integer[nodes.size()][nodes.size()]
nodes.each { n1 ->
  nodes.each { n2 ->
    if (n1 < n2) {
      n++
      sum += distance(nodeLocation[n1], nodeLocation[n2])
    }
    propagationDelay[n1-1][n2-1] = (int)(distance(nodeLocation[n1],nodeLocation[n2]) / channel.soundSpeed + 0.5)
  }
}

def avgRange = sum/n
println """Average internode distance: ${Math.round(avgRange)} m, delay: ${Math.round(1000*avgRange/channel.soundSpeed)} ms
TX Count\tRX Count\tLoss %\t\tOffered Load\tThroughput
--------\t--------\t------\t\t------------\t----------"""

File out = new File("logs/results.txt")
out.text = ''

  def load = 0.6
  simulate T, {

    // set up nodes
    nodes.each { myAddr ->
    
      // Divide network load across nodes evenly.
      float loadPerNode = load/nodes.size()
           
      def routingAgent = new Aodv()
      def macAgent = new Csma()
      //def motionAgent = new Motion()
      
      if(myAddr == 1)
      {
        def myNode = node("${myAddr}", address: myAddr, location: nodeLocation[myAddr], shell: true, stack: {container ->   
          container.add 'aodv', routingAgent
          container.add 'mac', macAgent
          //container.add 'motion', motionAgent
          })
      }
      else
      {
        def myNode = node("${myAddr}", address: myAddr, location: nodeLocation[myAddr], stack: {container ->   
          container.add 'aodv', routingAgent
          container.add 'mac', macAgent
          //container.add 'motion', motionAgent
          })
      }
      
      macAgent.dataMsgDuration    = (int)(8000*modem.frameLength[1]/modem.dataRate[1] + 0.5)
      macAgent.controlMsgDuration = (int)(8000*modem.frameLength[0]/modem.dataRate[0] + 0.5)
      macAgent.targetLoad         = loadPerNode

      //motionAgent.dimension = dimension
      
      routingAgent.dataMsgDuration    = (int)(8000*modem.frameLength[1]/modem.dataRate[1] + 0.5)
      routingAgent.controlMsgDuration = (int)(8000*modem.frameLength[0]/modem.dataRate[0] + 0.5)
      
      container.add 'load', new LoadGenerator(nodes-myAddr, loadPerNode)
    
    } // each
  
  } // simulation
  
  // display statistics
  float loss = trace.txCount ? 100*trace.dropCount/trace.txCount : 0
  println sprintf('%6d\t\t%6d\t\t%5.1f\t\t%7.3f\t\t%7.3f',
    [trace.txCount, trace.rxCount, loss, trace.offeredLoad, trace.throughput])

  //save to file
  out << "${trace.offeredLoad},${trace.throughput}\n"
