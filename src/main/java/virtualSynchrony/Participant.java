package virtualSynchrony;

import java.io.Serializable;
import scala.concurrent.duration.Duration;
import virtualSynchrony.Participant.JoinGroupMsg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;

public class Participant extends AbstractActor {
	
	private final int id;
	public int getId() {
		return id;
	}
	
	private static Object LOCK = new Object();
	private List<ActorRef> group;
	private List<ActorRef> newGroup = new ArrayList<>();			//used for view change flush participants
	private Random rnd = new Random();
	private int sendCount = 0;
	private boolean JoinNewAfterMulticast;
	final static int N_MESSAGES = 2;
	final static int VOTE_TIMEOUT = 1000;      // timeout for the votes, ms
	
	// a buffer storing all received chat messages
	private StringBuffer chatHistory = new StringBuffer();
	private List<ChatMsg> buffer =new ArrayList<>();
	  
	
	/* -- Actor constructor  --------------------------------------------------- */
	  
	public Participant(int id) {
		this.id = id;
	}
	static public Props props(int id) {
		return Props.create(Participant.class, () -> new Participant(id));
	}
	
	/* -- Message types ------------------------------------------------------- */
	
	// Start message that informs every chat participant about its peers
	public static class JoinGroupMsg implements Serializable {
		private final List<ActorRef> group;

		public JoinGroupMsg(List<ActorRef> group) {
			this.group = group;
		}
	}
	
	public static class PrintHistoryMsg implements Serializable {}
	
	public static class JoinNewAfterMulticast implements Serializable {
		private final boolean joinNew;
		public JoinNewAfterMulticast(boolean joinNew) {
			this.joinNew = joinNew;
		}
	}
	
	// A message requesting the peer to start a discussion on his topic
	public static class StartChatMsg implements Serializable {}
	
	public static class ChatMsg implements Serializable {
		    public final int n;          // the number of the reply in the current topic
		    public final int senderId;   // the ID of the message sender
		    public final boolean isStable;
		    public final boolean isFlush;
		    public ChatMsg(int n,int senderId, boolean isStable, boolean isFlush) {
		     
		      this.n = n;
		      this.senderId = senderId;
		      this.isStable = isStable;
		      this.isFlush = isFlush;
		      
		    }
		    
	  }
	
	public static class Timeout implements Serializable {
		public final int senderid;

		public Timeout(int senderid) {
			this.senderid=senderid;
		}}
	
	public static class FlushTimeout implements Serializable {
		private final List<ActorRef> group;

		public FlushTimeout(List<ActorRef> group) {
			this.group = group;
		}
	}
	
	public static class ViewChange implements Serializable {
		private final List<ActorRef> group;

		public ViewChange(List<ActorRef> group) {
			this.group = group;
		}
	}
	
	public static class ParticipantCrashed implements Serializable {
		public final int crashid;

		public ParticipantCrashed(int crashid) {
			this.crashid = crashid;
		}
	}
	
	private void sendChatMsg(int n)  {
		//limit the total msg exchangeg to N_MESSAGES
		while(sendCount<N_MESSAGES) {
			
			sendCount++; //number of messages broadcast
			n++;
			ChatMsg m = new ChatMsg(n,this.id, false, false);
			chatHistory.append(m.senderId+":"+m.n + "m ");
	    
	    
			ChatMsg m1 = new ChatMsg(n,this.id, true, false);
			// wait for normal message multicast to complete
			System.out.println(getSelf().path().name()+": multicasting unstable msg by "+ m.senderId);
			if(multicast(m)) {
				//multicast stable message
				multicast(m1);
			}
		}
	}
	
	/* -- Actor behaviour ----------------------------------------------------- */
	
	
	protected boolean crashed = false;          // simulates a crash
	
	private void onJoinGroupMsg(JoinGroupMsg msg) {
		this.group = msg.group;
	}
	private void onStartChatMsg(StartChatMsg msg) {
	    sendChatMsg(0); // start topic with message 0
	  }
	private void onChatMsg(ChatMsg msg)  {
		deliver(msg);  // "deliver" the message to the simulated chat user
	}
	
	private void printHistory(PrintHistoryMsg msg) {
	    System.out.printf("%s: %s\n", this.getSelf().path().name(), chatHistory);
	  }
	
	private void appendToHistory(ChatMsg m) {
		if(!m.isStable) {
			chatHistory.append(m.senderId+":"+m.n + "u ");
		}
		else {
			chatHistory.append(m.senderId+":"+m.n + "s ");
		}
	  }
	
	private boolean multicast(Serializable m) { // our multicast implementation
		
       List<ActorRef> shuffledGroup = new ArrayList<>(group);
	   Collections.shuffle(shuffledGroup);
	   for (ActorRef p: shuffledGroup) {
		   if (!p.equals(getSelf())) { // not sending to self
			   p.tell(m, getSelf());
			   try {
				   Thread.sleep(rnd.nextInt(10));
			   } 
			   catch (InterruptedException e) {
				   e.printStackTrace();
			   }
		   }
	  }
	   return true;
	}

	private void deliver(ChatMsg m)  {
		// for unstable messages
		if(!m.isStable && !m.isFlush) {
			this.buffer.add(m);
			appendToHistory(m);
			//set timeout, if timeout occurs it will call crashDetected method 
			setTimeout(VOTE_TIMEOUT,m.senderId);
			System.out.println(getSelf().path().name()+": sender " + m.senderId +": unstable msg recived");
			
			
			if(getSelf().equals(group.get(0)) && this.JoinNewAfterMulticast) {
				//creating new participant
				ActorRef newP= getContext().system().actorOf(Participant.props(group.size()), "participant" +group.size());
				this.group.add(newP);
				newP.tell(new JoinGroupMsg(group), null);
				
				ViewChange update = new ViewChange(group);
				//tell every one to update the group list and install new view
				for(ActorRef p:group) {
					p.tell(update, null);
				}
			}
			
		}
		//if msg is of flush type
		else if(m.isFlush) { 
			System.out.println("flush");
			this.newGroup.add(this.group.get(m.senderId));
			
		}
		// for stable messages
		else if(m.isStable && !group.get(3).equals(getSelf())) //stable msg is not received to 3rd participant
			{
			
			//removing stable msg from buffer
			for (Iterator<ChatMsg> iterator = this.buffer.iterator(); iterator.hasNext(); ) {
				ChatMsg value = iterator.next();
			    if (m.senderId==value.senderId) {
			    	System.out.println(getSelf().path().name()+": sender " + m.senderId +": stable msg recived");
			        iterator.remove();
			    }
			}
			appendToHistory(m);
		}
	    
	}
	
	// schedule a Timeout message in specified time
    private void setTimeout(int time,int senderid) {
      getContext().system().scheduler().scheduleOnce(
          Duration.create(time, TimeUnit.MILLISECONDS),  
          getSelf(),
          new Timeout(senderid), // the message to send
          getContext().system().dispatcher(), getSelf()
          );
    }
    
    private void onTimeout(Timeout msg) {                           /* Timeout */
    	boolean stable= true;
    	
    	//check if participant received stable msg
    	if(!this.buffer.isEmpty()) {
    		for(ChatMsg p: this.buffer) {
    			if(p.senderId==msg.senderid) {
    				stable=false;
    			}
    		}
    	}
    	
    	//if stable msg is not received, tell GM to install new view
    	if(!stable) {
    		// Tell GM to remove the crashed Participant
    		System.out.println(getSelf().path().name()+": TIMEOUT: stable not recived from " + msg.senderid);
        	group.get(0).tell(new ParticipantCrashed(msg.senderid), null);
    	}
    			 
	}
    
    private void onJoinNewAfterMulticast(JoinNewAfterMulticast msg) {
    	this.JoinNewAfterMulticast= msg.joinNew;
    }

    private void onFlushTimeout(FlushTimeout msg) {
    	this.group = msg.group;
    }
    
	private void onViewChange(ViewChange list) {					/* View changed*/
		//TODO 1) stop all multicast
		
		if(!this.buffer.isEmpty()) {	//check if buffer is not empty mean there is some unstable msg received by current participant.
			for(ChatMsg tmp : this.buffer) {
			    chatHistory.append(tmp.senderId+":"+tmp.n + "m ");
			    
			    System.out.println(getSelf().path().name()+": multicasting unstable msg from buffer by "+ tmp.senderId);
			    ChatMsg m= new ChatMsg(tmp.n,tmp.senderId,true,false);
			    if(multicast(m)) {			// 2) multicast unstable msg
			    
			    }
			}
		}

		
		
		// 4) wait for flush from every one in new view
		/*try {
			synchronized(LOCK){
				while(!allFlush(list.group)){
					LOCK.wait();	
				}
				System.out.println("hello");
				this.group=list.group;
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}*/
    	System.out.println("multicasting flush msg by "+ this.id);
		ChatMsg m= new ChatMsg(0,this.id,false,true);
    	multicast(m);			// 3) multicast flush to every one
    	chatHistory.append(m.senderId+":"+m.n + "fm ");
    
		
		
		// 4) wait for flush from every one in new view
		getContext().system().scheduler().scheduleOnce(
		          Duration.create(6000, TimeUnit.MILLISECONDS),  
		          getSelf(),
		          new FlushTimeout(list.group), // the message to send
		          getContext().system().dispatcher(), getSelf()
		          );
		
		// 5) install the view
		
	}
	
	//check whether all the active participants have send the flush msg
	/*private boolean allFlush(List<ActorRef> group) {
		return new HashSet<>(group).equals(new HashSet<>(this.newGroup));
	}*/
	
	@Override
	public Receive createReceive() {
		return receiveBuilder()
				.match(JoinGroupMsg.class, this::onJoinGroupMsg)
				.match(PrintHistoryMsg.class, this::printHistory)
				.match(ChatMsg.class,         this::onChatMsg)
				.match(StartChatMsg.class,    this::onStartChatMsg)
				.match(Timeout.class, this::onTimeout)
				.match(ViewChange.class, this::onViewChange)
				.match(ParticipantCrashed.class, this::onParticipantCrashed)
				.match(FlushTimeout.class, this::onFlushTimeout)
				.match(JoinNewAfterMulticast.class, this::onJoinNewAfterMulticast)
				.build();
	}
	
	/* -- GM behaviour ----------------------------------------------------- */
	private void onParticipantCrashed(ParticipantCrashed msg) {
		System.out.println(getSelf().path().name()+": "+group.get(msg.crashid).path().name() + ": crashed: installing new view");
		if(group.get(msg.crashid) != null ) {
			//removing crashed participant from the group
			group.remove(msg.crashid);
			ViewChange update = new ViewChange(group);
			//tell every one to update the group list and install new view
			for(ActorRef p:group) {
				if (!p.equals(getSelf())) { // not sending to self
					   p.tell(update, null);
				}
			}
		}
		
		else {
			return;
		}
		
		
	}
	
}
