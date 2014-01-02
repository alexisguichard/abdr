package db;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentHashMap;

import monitor.Monitor;
import oracle.kv.DurabilityException;
import oracle.kv.FaultException;
import oracle.kv.KVStore;
import oracle.kv.KVStoreConfig;
import oracle.kv.KVStoreFactory;
import oracle.kv.Key;
import oracle.kv.KeyRange;
import oracle.kv.OperationExecutionException;
import oracle.kv.OperationFactory;
import oracle.kv.Value;
import oracle.kv.ValueVersion;
import transaction.Data;
import transaction.DeleteOperation;
import transaction.Operation;
import transaction.OperationResult;
import transaction.ReadOperation;
import transaction.WriteOperation;

/** 
 * gestion d'une base de donnée
 * execution des transactions, va gérer la migration
 * 	- convertissement des clés principales pour avoir une clé principale unique pour la transaction
 * 	- tu fais la transaction
 * 	- une fois que c'est fait, on retransforme les catégories tel qu'ils étaient
 * 
 * 	- répartion de type anneau, + notification au moniteur des migrations
 * 
 * transformer = multiget, transaction write...
 * 
 * @author 2600705
 *
 */

public class KVDB implements KVDBInterface {
	private KVStore store;
	private int id;
    private String storeName = "kvstore";
    private String hostName = "localhost";
    private String hostPort = "5000";
    
    private final int nbInt = 5;
    private final int nbString = 5;
    private final int nbObjects = 5;
    private final int nbProfile = 5;

    private Map<String, KVDB> kvdbs = new HashMap<String, KVDB>();
    private Map<Integer, Monitor> monitorMapping = new ConcurrentHashMap<Integer, Monitor>();
    private List<Integer> profiles = new ArrayList<Integer>();
    
	
	public KVDB(int id, String storeName, String hostName, String hostPort, Map<Integer, Monitor> monitorMapping) {
		this.id = id;
		this.storeName = storeName;
		this.hostName = hostName;
		this.hostPort = hostPort;
		this.monitorMapping = monitorMapping;
		initAll();
	}
	
	
	
	public int getId() {
		return id;
	}
	
	public void setLeftKVDB(KVDB kvdbLeft) {
		this.kvdbs.put("left", kvdbLeft);
	}
	
	public void setRightKVDB(KVDB kvdbRight) {
		this.kvdbs.put("right", kvdbRight);
	}

	public Map<String, KVDB> getKvdbs() {
		return kvdbs;
	}

	
	private void initBase() {
        Key key;

        //instanciation de la base de donnée
		try {
			//TODO lire le fichier de conf pour avoir les info de configuration
		      store = KVStoreFactory.getStore(new KVStoreConfig(storeName, hostName + ":" + hostPort));
		} catch (Exception e) {
		    e.printStackTrace();
		}
        
        //foreach profile
       for (int i = id; i < (id + nbProfile); i++) {
        	profiles.add(i);
        	//foreach object
            for (int j = 0; j < nbObjects; j++) {
            	//foreach attribute
            	for (int k = 0; k < nbString + nbInt; k++) {
            		List<String> att = new ArrayList<String>();
            		att.add(new Integer(j).toString());
            		att.add(new Integer(k).toString());
            		key = Key.createKey("" + i,  att);
            		store.put(key, Value.createValue(new Integer(0).toString().getBytes()));
            	}
            }
        }
    }
	
	private void initLoadBalancer() {
		Thread t = new Thread(new KVDBLoadBalancer(this));
		t.start();
	}
	
	private void initAll() {
		initBase();
		initLoadBalancer();
	}
	
	
	@Override
	public List<OperationResult> executeOperations(List<Operation> operations) {
		List<OperationResult> result = new ArrayList<OperationResult>();
		
		//si c'est un simple get, on fait un multiget sur la clé
		if ((operations.size() == 1) && (operations.get(0) instanceof ReadOperation)) {
			result = getData(operations.get(0).getData());
		}
		else {
			//sinon, c'est une transaction
			
			//if the operation has multiple key : 
			//fetch tables
			//convert them to one category
			//execute transaction
			//retransform to multiple key
			boolean hasExecuted = false;
			List<oracle.kv.Operation> opList = convertOperations(operations);
			do {
			    try {
					List<oracle.kv.OperationResult> res = store.execute(opList);
					result.addAll(KVResult2OperationResult(res));
					hasExecuted = true;
					
				} catch (DurabilityException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (OperationExecutionException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (FaultException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} while (! hasExecuted);
		}
		
		return result;
	}
	
	/**
	 * Get data object associated with an id and category
	 * @param data
	 * @return
	 */
	private List<OperationResult> getData (Data data) {
		int dataId = data.getId();
		int category = data.getCategory();
		List <OperationResult> operationResult = new ArrayList<OperationResult>();
		
		SortedMap<Key,ValueVersion> profileObjects = store.multiGet(Key.createKey("" + category), new KeyRange (new Integer(dataId).toString()), null);

		if (profileObjects.size() != (nbString + nbInt)) {
			operationResult.add(new OperationResult(false, null));
			return operationResult;
		}
		
		int i = 0;
		for (Entry<Key, ValueVersion> profileObject : profileObjects.entrySet()) {
			String value = new String (profileObject.getValue().getValue().getValue());
			if (i < nbInt)
				data.getListNumber().add(Integer.valueOf(value));
    		else
    			data.getListString().add(value);
    		i++;
		}
		
		operationResult.add(new OperationResult(true, data));
		
		return operationResult;
	}
	
	
	private List<OperationResult> KVResult2OperationResult (List<oracle.kv.OperationResult> kvResult) {
		List <OperationResult> operationResult = new ArrayList<OperationResult>();
		for (int i = 0; i < kvResult.size(); i += 10) {
			operationResult.add(new OperationResult(kvResult.get(i).getSuccess(), null));
		}

		return operationResult;
	}
	
	
	private List<oracle.kv.Operation> convertOperations (List<Operation> operations) {
		List<oracle.kv.Operation> operationList = new ArrayList<oracle.kv.Operation>();

		for (Operation operation : operations) {
			if (operation instanceof WriteOperation) {
				operationList.addAll(getAddDataTransaction(operation.getData()));
			}
			else {
				operationList.addAll(getRemoveDataTransaction(operation.getData()));
			}
		}
		
		return operationList;
	}
	
	/**
	 * When overloaded, and having a token, transfuse to the token unused tables
	 * @param profiles
	 * @param target
	 */
	@Override
	public void transfuseData(int profile, KVDB target) {
		List<Data> unusedData = new ArrayList<Data>();
		unusedData.addAll(getAllDataFromProfile(profile));
		
		//data 2 transaction
		List<Operation> transfuseOperations = new ArrayList<Operation>();
		for (Data data : unusedData) {
			transfuseOperations.add(new WriteOperation(data));
		}
		
		//add them to target (inject)
		target.injectData(transfuseOperations);
		
		//remove them from here (delete)
		List<Operation> deleteOperations = new ArrayList<Operation>();
		for (Data data : unusedData) {
			deleteOperations.add(new DeleteOperation(data));
		}
		executeOperations(deleteOperations);
	}
	
	
	private List<Data> getAllDataFromProfile(int profile) {
		List<Data> datas = new ArrayList<Data>();

		SortedMap<Key,ValueVersion> profileObjects = store.multiGet(Key.createKey("" + profile), null, null);
		int i = 0;
		Data data = new Data();
		for (Entry<Key, ValueVersion> profileObject : profileObjects.entrySet()) {
			String value = new String (profileObject.getValue().getValue().getValue());
			if (i < nbInt)
				data.getListNumber().add(Integer.valueOf(value));
    		else
    			data.getListString().add(value);
    		i++;
    		
    		if (i == 10) {
				i = 0;
				data.setId(Integer.valueOf(profileObject.getKey().getMinorPath().get(0)));
				data.setCategory(Integer.valueOf(profileObject.getKey().getMajorPath().get(0)));
				datas.add(data);
				data = new Data();
    		}
		}
		
		return datas;
	}
	
	/**
	 * Create a list of operations to add a data object
	 * @param data
	 * @return
	 */
	private List<oracle.kv.Operation> getAddDataTransaction (Data data) {
		int dataId = data.getId();
		int category = data.getCategory();
		Key key;
		OperationFactory operationFactory = store.getOperationFactory();
		List<oracle.kv.Operation> operationList = new ArrayList<oracle.kv.Operation>();
		
		if (! profiles.contains(category))
			profiles.add(category);
		
		for (int i = 0; i < nbInt + nbString; i++) {
			List<String> att = new ArrayList<String>();
    		att.add(new Integer(dataId).toString());
    		att.add(new Integer(i).toString());
    		key = Key.createKey("" + category, att);
    		
    		if (i < nbInt)
    			operationList.add(operationFactory.createPut(key, Value.createValue(data.getListNumber().get(i).toString().getBytes())));
    		else
    			operationList.add(operationFactory.createPut(key, Value.createValue(data.getListString().get(i - nbInt).toString().getBytes())));
		}
		
		return operationList;
	}
	
	@Override
	public String toString() {
		return "KVDB [id=" + id + ", storeName=" + storeName + ", profiles="
				+ profiles + "]";
	}

	/**
	 * Create a list of operations to delete a data object
	 * @param data
	 * @return
	 */
	private List<oracle.kv.Operation> getRemoveDataTransaction (Data data) {
		int dataId = data.getId();
		int category = data.getCategory();
		Key key;
		OperationFactory operationFactory = store.getOperationFactory();
		List<oracle.kv.Operation> operationList = new ArrayList<oracle.kv.Operation>();
		
		if (! profiles.contains(category))
			profiles.add (category);
		
		for (int i = 0; i < nbInt + nbString; i++) {
			List<String> att = new ArrayList<String>();
    		att.add(new Integer(dataId).toString());
    		att.add(new Integer(i).toString());
    		key = Key.createKey("" + category, att);
    		operationList.add(operationFactory.createDelete(key));
		}
		
		return operationList;
	}
	
	
	/**thread qui doit passer les jetons dans un anneau
		si moi même je ne fais rien, j'envoi un jeton qui doit faire le tour
			si les autres sont surchargés, le premier qui recoit mon jeton m'envoie les tables sur lequel il ne travaille pas trop (le surchargé garde les gros)
			je détecte qu'on menvoie du travail en regardant mon jeton recut : 
			 	- si jeton vide, alors il a faits le tour de l'anneau
			 	- sinon, on m'envoiee du travail
	*/
	
	@Override
	public void injectData(List<Operation> data) {
		executeOperations(data);
	}
	
	/**
	 * ask migration from kvdb having profiles v to me
	 * @param profiles
	 */
	public void migrate(List<Integer> profiles) {
		List<KVDB> targetServers = new ArrayList<KVDB>();
		
		//ask migration
		for (Integer profile : profiles) {
			targetServers.add(monitorMapping.get(profile).notifyMigration(this, profile));
		}
		
		//begin migration from target to me
		for (int i = 0; i < profiles.size(); i++) {
			targetServers.get(i).transfuseData(profiles.get(i), this);
		}
		
		//end migration
		for (Integer profile : profiles) {
			monitorMapping.get(profile).notifyEndMigration(this, profile);
		}
	}
	
	@Override
	public void printDB() {
        //foreach profile
		for (Integer profile : profiles) {
			SortedMap<Key,ValueVersion> profileObjects = store.multiGet(Key.createKey(profile + ""), null, null);
			
			for (Entry<Key, ValueVersion> profileObject : profileObjects.entrySet()) {
				ValueVersion valueVersion = profileObject.getValue();
				Key key = profileObject.getKey();
				
				System.out.println("id = " + id + " clé = " + key + ", valeur = " + new String(valueVersion.getValue().getValue()));
			}
		}
    }
	
	@Override
	public void closeDB() {
		for (Integer profile : this.profiles) {
			store.multiDelete(Key.createKey("" + profile), null, null);
		}
		store.close();
	}
	

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + id;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		KVDB other = (KVDB) obj;
		if (id != other.id)
			return false;
		return true;
	}
}
