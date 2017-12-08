import org.arl.fjage.*
import org.arl.unet.*
import org.arl.unet.nodeinfo.*

class Motion extends UnetAgent
{
  private AgentID node

  private int myAddr
  private double[] coordinates
  private double distance

  private enum State {
    REST, MOTION
  }

  private FSMBehavior fsm = FSMBuilder.build {

    state(State.REST) {
      onEnter {
        node.mobility = false
        after(rnd(60, 300).seconds) {
          setNextState(State.MOTION)
        }
      }
    }

    state(State.MOTION) {

      onEnter {
        def bo = AgentLocalRandom.current().nextDouble(0.1, 0.4).mps

        def x  = AgentLocalRandom.current().nextInt(dimension).m    // x-coordinate of the point
        def y  = AgentLocalRandom.current().nextInt(dimension).m    // y-coordinate of the point
        def z = -15.m                                               // z-coordinate is constant in our case.

        double[] newlocation = [x, y, z]
        distance = Math.sqrt(
                              (newlocation[0] - coordinates[0]) * (newlocation[0] - coordinates[0]) +
                              (newlocation[1] - coordinates[1]) * (newlocation[1] - coordinates[1]) +
                              (newlocation[2] - coordinates[2]) * (newlocation[2] - coordinates[2])
                            )
        node.mobility = true
        node.speed = bo

        if (coordinates[0] < newlocation[0])
        {
          double a = newlocation[0] - coordinates[0]
          if (coordinates[1] < newlocation[1])
          {
            double b = newlocation[1] - coordinates[1]
            node.heading = Math.toDegrees(Math.atan2(a, b))
          }

          else if (coordinates[1] == newlocation[1])
          {
            node.heading = 90.degrees
          }

          else if (coordinates[1] > newlocation[1])
          {
            double b = coordinates[1] - newlocation[1]
            node.heading = 90.degrees + Math.toDegrees(Math.atan2(a, b))
          }
        }

        else if (coordinates[0] == newlocation[0])
        {
          if (coordinates[1] < newlocation[1])
          {
            node.heading = 0.degrees
          }

          else if (coordinates[1] == newlocation[1])
          {
            node.heading = 0.degrees
          }

          else if (coordinates[1] > newlocation[1])
          {
            node.heading = 180.degrees
          }
        }

        else if (coordinates[0] > newlocation[0])
        {
          double a = coordinates[0] - newlocation[0]
          if (coordinates[1] < newlocation[1])
          {
            double b = newlocation[1] - coordinates[1]
            node.heading = 270.degrees + Math.toDegrees(Math.atan2(a, b))
          }

          else if (coordinates[1] == newlocation[1])
          {
            node.heading = 270.degrees
          }

          else if (coordinates[1] > newlocation[1])
          {
            double b = coordinates[1] - newlocation[1]
            node.heading = 270.degrees - Math.toDegrees(Math.atan2(a, b))
          }
        }

        after(distance/bo) {
          setNextState(State.REST)
        }
      }
    }
  }

  @Override
  void startup()
  {
    node = agentForService(Services.NODE_INFO)
    node.mobility = true

    myAddr = node.Address
    coordinates = node.location
    add(fsm)
  }

  int dimension // Parameter to be assigned by the Simulation file.

}