package voldemort.store.readonly.mr;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.List;

import org.apache.avro.generic.GenericData;
import org.apache.avro.mapred.AvroCollector;
import org.apache.avro.mapred.AvroMapper;
import org.apache.avro.mapred.Pair;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobConfigurable;
import org.apache.hadoop.mapred.Reporter;

import voldemort.VoldemortException;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.routing.ConsistentRoutingStrategy;
import voldemort.serialization.DefaultSerializerFactory;
import voldemort.serialization.SerializerDefinition;
import voldemort.serialization.SerializerFactory;
import voldemort.serialization.avro.AvroGenericSerializer;
import voldemort.store.StoreDefinition;
import voldemort.store.compress.CompressionStrategy;
import voldemort.store.compress.CompressionStrategyFactory;
import voldemort.store.readonly.mr.azkaban.StoreBuilderTransformation;
import voldemort.store.readonly.mr.utils.HadoopUtils;
import voldemort.utils.ByteUtils;
import voldemort.xml.ClusterMapper;
import voldemort.xml.StoreDefinitionsMapper;
import azkaban.common.utils.Props;
import azkaban.common.utils.Utils;

public class AvroStoreBuilderMapper extends
        AvroMapper<GenericData.Record, Pair<ByteBuffer, ByteBuffer>> implements JobConfigurable {

    protected MessageDigest md5er;
    protected ConsistentRoutingStrategy routingStrategy;
    protected AvroGenericSerializer keySerializer;
    protected AvroGenericSerializer valueSerializer;

    private String keySchema;
    private String valSchema;

    private String keyField;
    private String valField;

    private String _keySelection;
    private String _valSelection;

    private StoreBuilderTransformation _keyTrans;
    private StoreBuilderTransformation _valTrans;

    private CompressionStrategy valueCompressor;
    private CompressionStrategy keyCompressor;
    private SerializerDefinition keySerializerDefinition;
    private SerializerDefinition valueSerializerDefinition;

    // Path path = new Path(fileName);
    FSDataOutputStream outputStream;

    /**
     * Create the voldemort key and value from the input key and value and map
     * it out for each of the responsible voldemort nodes
     * 
     * The output key is the md5 of the serialized key returned by makeKey().
     * The output value is the node_id & partition_id of the responsible node
     * followed by serialized value returned by makeValue() OR if we have
     * setKeys flag on the serialized key and serialized value
     */
    @Override
    public void map(GenericData.Record record,
                    AvroCollector<Pair<ByteBuffer, ByteBuffer>> collector,
                    Reporter reporter) throws IOException {

        byte[] keyBytes = keySerializer.toBytes(record.get(keyField));
        byte[] valBytes = valueSerializer.toBytes(record.get(valField));

        // Compress key and values if required
        if(keySerializerDefinition.hasCompression()) {
            keyBytes = keyCompressor.deflate(keyBytes);
        }

        if(valueSerializerDefinition.hasCompression()) {
            valBytes = valueCompressor.deflate(valBytes);
        }

        // Get the output byte arrays ready to populate
        byte[] outputValue;
        BytesWritable outputKey;

        // Leave initial offset for (a) node id (b) partition id
        // since they are written later
        int offsetTillNow = 2 * ByteUtils.SIZE_OF_INT;

        if(getSaveKeys()) {

            // In order - 4 ( for node id ) + 4 ( partition id ) + 1 (
            // replica
            // type - primary | secondary | tertiary... ] + 4 ( key size )
            // size ) + 4 ( value size ) + key + value
            outputValue = new byte[valBytes.length + keyBytes.length + ByteUtils.SIZE_OF_BYTE + 4
                                   * ByteUtils.SIZE_OF_INT];

            // Write key length - leave byte for replica type
            offsetTillNow += ByteUtils.SIZE_OF_BYTE;
            ByteUtils.writeInt(outputValue, keyBytes.length, offsetTillNow);

            // Write value length
            offsetTillNow += ByteUtils.SIZE_OF_INT;
            ByteUtils.writeInt(outputValue, valBytes.length, offsetTillNow);

            // Write key
            offsetTillNow += ByteUtils.SIZE_OF_INT;
            System.arraycopy(keyBytes, 0, outputValue, offsetTillNow, keyBytes.length);

            // Write value
            offsetTillNow += keyBytes.length;
            System.arraycopy(valBytes, 0, outputValue, offsetTillNow, valBytes.length);

            // Generate MR key - upper 8 bytes of 16 byte md5
            outputKey = new BytesWritable(ByteUtils.copy(md5er.digest(keyBytes),
                                                         0,
                                                         2 * ByteUtils.SIZE_OF_INT));

        } else {

            // In order - 4 ( for node id ) + 4 ( partition id ) + value
            outputValue = new byte[valBytes.length + 2 * ByteUtils.SIZE_OF_INT];

            // Write value
            System.arraycopy(valBytes, 0, outputValue, offsetTillNow, valBytes.length);

            // Generate MR key - 16 byte md5
            outputKey = new BytesWritable(md5er.digest(keyBytes));

        }

        // Generate partition and node list this key is destined for
        List<Integer> partitionList = routingStrategy.getPartitionList(keyBytes);
        Node[] partitionToNode = routingStrategy.getPartitionToNode();

        for(int replicaType = 0; replicaType < partitionList.size(); replicaType++) {

            // Node id
            ByteUtils.writeInt(outputValue,
                               partitionToNode[partitionList.get(replicaType)].getId(),
                               0);

            if(getSaveKeys()) {
                // Primary partition id
                ByteUtils.writeInt(outputValue, partitionList.get(0), ByteUtils.SIZE_OF_INT);

                // Replica type
                ByteUtils.writeBytes(outputValue,
                                     replicaType,
                                     2 * ByteUtils.SIZE_OF_INT,
                                     ByteUtils.SIZE_OF_BYTE);
            } else {
                // Partition id
                ByteUtils.writeInt(outputValue,
                                   partitionList.get(replicaType),
                                   ByteUtils.SIZE_OF_INT);
            }
            BytesWritable outputVal = new BytesWritable(outputValue);

            // System.out.println("collect length (K/V): "+
            // outputKey.getLength()+ " , " + outputVal.getLength());
            ByteBuffer keyBuffer = null, valueBuffer = null;

            byte[] md5KeyBytes = outputKey.getBytes();
            keyBuffer = ByteBuffer.allocate(md5KeyBytes.length);
            keyBuffer.put(md5KeyBytes);
            keyBuffer.rewind();

            valueBuffer = ByteBuffer.allocate(outputValue.length);
            valueBuffer.put(outputValue);
            valueBuffer.rewind();

            Pair<ByteBuffer, ByteBuffer> p = new Pair<ByteBuffer, ByteBuffer>(keyBuffer,
                                                                              valueBuffer);

            collector.collect(p);
        }
        md5er.reset();
    }

    @Override
    public void configure(JobConf conf) {

        super.setConf(conf);
        // from parent code

        md5er = ByteUtils.getDigest("md5");

        this.cluster = new ClusterMapper().readCluster(new StringReader(conf.get("cluster.xml")));
        List<StoreDefinition> storeDefs = new StoreDefinitionsMapper().readStoreList(new StringReader(conf.get("stores.xml")));
        if(storeDefs.size() != 1)
            throw new IllegalStateException("Expected to find only a single store, but found multiple!");
        this.storeDef = storeDefs.get(0);

        this.numChunks = conf.getInt("num.chunks", -1);
        if(this.numChunks < 1)
            throw new VoldemortException("num.chunks not specified in the job conf.");

        this.saveKeys = conf.getBoolean("save.keys", true);
        this.reducerPerBucket = conf.getBoolean("reducer.per.bucket", false);

        keySerializerDefinition = getStoreDef().getKeySerializer();
        valueSerializerDefinition = getStoreDef().getValueSerializer();

        try {
            SerializerFactory factory = new DefaultSerializerFactory();

            if(conf.get("serializer.factory") != null) {
                factory = (SerializerFactory) Class.forName(conf.get("serializer.factory"))
                                                   .newInstance();
            }

            keyField = conf.get("avro.key.field");
            valField = conf.get("avro.value.field");

            keySchema = conf.get("avro.key.schema");
            valSchema = conf.get("avro.val.schema");

            // hadoop.job.valueSchema
            keySerializer = new AvroGenericSerializer(keySchema);
            valueSerializer = new AvroGenericSerializer(valSchema);
        } catch(Exception e) {
            throw new RuntimeException(e);
        }

        keyCompressor = new CompressionStrategyFactory().get(keySerializerDefinition.getCompression());
        valueCompressor = new CompressionStrategyFactory().get(valueSerializerDefinition.getCompression());

        routingStrategy = new ConsistentRoutingStrategy(getCluster().getNodes(),
                                                        getStoreDef().getReplicationFactor());

        // /
        Props props = HadoopUtils.getPropsFromJob(conf);

        _keySelection = props.getString("key.selection", null);
        _valSelection = props.getString("value.selection", null);

        String _keyTransClass = props.getString("key.transformation.class", null);
        String _valueTransClass = props.getString("value.transformation.class", null);

        if(_keyTransClass != null)
            _keyTrans = (StoreBuilderTransformation) Utils.callConstructor(_keyTransClass);
        if(_valueTransClass != null)
            _valTrans = (StoreBuilderTransformation) Utils.callConstructor(_valueTransClass);
    }

    private int numChunks;
    private Cluster cluster;
    private StoreDefinition storeDef;
    private boolean saveKeys;
    private boolean reducerPerBucket;

    public Cluster getCluster() {
        checkNotNull(cluster);
        return cluster;
    }

    public boolean getSaveKeys() {
        return this.saveKeys;
    }

    public boolean getReducerPerBucket() {
        return this.reducerPerBucket;
    }

    public StoreDefinition getStoreDef() {
        checkNotNull(storeDef);
        return storeDef;
    }

    public String getStoreName() {
        checkNotNull(storeDef);
        return storeDef.getName();
    }

    private final void checkNotNull(Object o) {
        if(o == null)
            throw new VoldemortException("Not configured yet!");
    }

    public int getNumChunks() {
        return this.numChunks;
    }

}