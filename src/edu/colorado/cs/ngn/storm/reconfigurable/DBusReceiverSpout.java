package edu.colorado.cs.ngn.storm.reconfigurable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.codec.binary.Base64;
import org.freedesktop.dbus.DBusAsyncReply;
import org.freedesktop.dbus.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;

import backtype.storm.Config;
import backtype.storm.StormSubmitter;
import backtype.storm.generated.AlreadyAliveException;
import backtype.storm.generated.InvalidTopologyException;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseRichSpout;
import backtype.storm.tuple.Fields;
import edu.colorado.cs.ngn.sdihc.Switch;

/**
 * This spout will sit before each "real" Storm bolt and connect to DBus.
 * All input for each "real" bolt will be received from DBus
 * TODO: make into abstract class -> cannot declare output fields 
 * after the Storm launches the object
 * @author michael
 *
 */
public class DBusReceiverSpout extends BaseRichSpout {

	/**
	 * autogenerated id
	 */
	private static final long serialVersionUID = 1L;
	public static final String DBUS_ADDRESS = "unix:path=/tmp/ipc_switch_bus";
	public static final String SWITCH_OBJECT_PATH = "/";
	private DBusReceiverTask dbusReceiver = null;
	private ConcurrentLinkedQueue<List<Object>> tupleQueue;
	private SpoutOutputCollector collector;
	private ExecutorService execService;
	private String pe_id;
	public final static int SHUTDOWN_TIMEOUT = 5000;
	/**
	 * id of dbus system to connect to
	 * TODO: verify this address
	 */
	public final static String CONNECTION_ID = "edu.colorado.cs.ngn.sdihc.Switch";
	
	public DBusReceiverSpout(String pe_id){
		super();
		this.pe_id = pe_id;
	}
	
	@Override
	public void nextTuple() {
		if(!tupleQueue.isEmpty()){
			List<Object> tuple = new ArrayList<Object>();
			tuple.add(tupleQueue.poll());
			collector.emit(tuple);
		}
	}

	@Override
	public void open(@SuppressWarnings("rawtypes") Map arg0, TopologyContext arg1, SpoutOutputCollector arg2) {
		//need to connect to DBus with some id
		tupleQueue = new ConcurrentLinkedQueue<List<Object>>();
		execService = Executors.newCachedThreadPool();
		launch_dbus_receiver();
		this.collector = arg2;
	}

	private void launch_dbus_receiver() {
		dbusReceiver = new DBusReceiverTask(this, pe_id);
		execService.execute(dbusReceiver);
	}

	
	@Override
	public void close(){
		if(dbusReceiver != null){
			dbusReceiver.shutdown();
		}
		execService.shutdown();
		try {
			execService.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			System.out.println("Could not shutdown executor service in "+SHUTDOWN_TIMEOUT+ " ms in "+this.getClass().getCanonicalName());
		}
		super.close();
	}
	
	public static List<Object> unflattenDBusList(String objectList){
		ByteArrayInputStream bis = new ByteArrayInputStream(Base64.decodeBase64(objectList));
		ObjectInput in = null;
		List<Object> list = null;
		try {
			in = new ObjectInputStream(bis);
			list = (List<Object>) in.readObject();
		} catch (IOException e) {
			System.out.println("Could not creare ObjectInputStream");
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			System.out.println("Could not create class from byte array");
			e.printStackTrace();
		} finally{
			try {
				bis.close();
			} catch (IOException e) {}
			
			if(in != null){
				try {
					in.close();
				} catch (IOException e) {}
			}
		}
		return list;
	}
	
	public static String flattenDBusList(List<Object> objectList){
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		ObjectOutput out = null;
		byte[] objectBytes = null;
		try{
			
			out = new ObjectOutputStream(bos);
			out.writeObject(objectList);
			objectBytes = bos.toByteArray();
		} catch (IOException e) {
			System.out.println("Could not create ObjectOutputStream or write object to byte array");
			e.printStackTrace();
		} finally{
			if(out != null){
				try {
					out.close();
				} catch (IOException e) {}
			}
			try {
				bos.close();
			} catch (IOException e) {}
		}
		return Base64.encodeBase64String(objectBytes);
	}
	
	private class DBusReceiverTask implements Runnable{

		private DBusConnection connection = null;
		private AtomicBoolean boltExecuting = new AtomicBoolean(true);
		private DBusReceiverSpout receiverSpout;
		private String pe_id;
		
		public DBusReceiverTask(DBusReceiverSpout receiverSpout, String pe_id){
			this.receiverSpout = receiverSpout;
			this.pe_id = pe_id;
		}
		
		@Override
		public void run() {
			try {
//				connection = DBusConnection.getConnection(DBusConnection.SESSION);
				connection = DBusConnection.getConnection(DBusReceiverSpout.DBUS_ADDRESS );
			} catch (DBusException e) {
				System.out.println("Could not connect to the DBus");
				e.printStackTrace();
			}
			Switch dbusSwitch;
			try {
				dbusSwitch = connection.getRemoteObject(DBusReceiverSpout.CONNECTION_ID, DBusReceiverSpout.SWITCH_OBJECT_PATH, Switch.class);
				while(boltExecuting.get()){
					try {
//						Switch dbusSwitch = connection.getRemoteObject(DBusReceiverSpout.CONNECTION_ID, "/edu/colorado/cs/ngn/sdipc/Switch", Switch.class);
						
//						String data = dbusSwitch.Dequeue(pe_id);
						DBusAsyncReply<String> reply = connection.callMethodAsync(dbusSwitch, "Dequeue", pe_id);
						
						Thread.sleep(5000);
						
						String data = reply.getReply();
						
						if(data.length() > 0){
							List<Object> receivedTuple = DBusReceiverSpout.unflattenDBusList(data);
							if(receivedTuple != null){
								receiverSpout.tupleQueue.add(receivedTuple);
							}
						}
						System.out.println("Received data from dbus: "+data);
//						List<Object> tuple = new ArrayList<Object>();
//						tuple.add(data);
//						receiverSpout.tupleQueue.add(tuple);
					} catch(java.lang.ExceptionInInitializerError e){
					} catch(java.lang.NoClassDefFoundError e){
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			} catch (DBusException e1) {
				//is ok if we could not parse an object. Just try again on the next iteration
				System.out.println("Could not get remote object: "+DBusReceiverSpout.CONNECTION_ID);
			}
			
		}

		public void shutdown() {
			boltExecuting.set(false);
			connection.disconnect();
		}
	}
	
	public static void main(String[] args) throws AlreadyAliveException, InvalidTopologyException{
		TopologyBuilder builder = new TopologyBuilder();
		
		builder.setSpout("w1_recv", new DBusReceiverSpout("w1"), 1);
		builder.setBolt("w1_send", new DBusSenderBolt("w2"), 1).shuffleGrouping("w1_recv");
		
		builder.setSpout("w2_recv", new DBusReceiverSpout("w2"), 1);
		builder.setBolt("w2_send", new DBusSenderBolt("w2"), 1).shuffleGrouping("w2_recv");
		
		builder.setSpout("w3_recv", new DBusReceiverSpout("w3"), 1);
		builder.setBolt("w3_send", new DBusSenderBolt("w3"), 1).shuffleGrouping("w3_recv");
		
		builder.setSpout("w4_recv", new DBusReceiverSpout("w4"), 1);
		builder.setBolt("w4_send", new DBusSenderBolt("w4"), 1).shuffleGrouping("w4_recv");
		
		Config conf = new Config();
		conf.setNumWorkers(1);
//		conf.setDebug(true);
		
		StormSubmitter.submitTopology("dbus_test_topology", conf, builder.createTopology());
	}

	@Override
	public void declareOutputFields(OutputFieldsDeclarer arg0) {
		arg0.declare(new Fields("data"));
		
	}

}
