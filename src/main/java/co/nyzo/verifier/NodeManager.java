package co.nyzo.verifier;

import co.nyzo.verifier.messages.NodeJoinMessage;
import co.nyzo.verifier.messages.BootstrapRequest;
import co.nyzo.verifier.messages.BootstrapResponse;
import co.nyzo.verifier.util.IpUtil;
import co.nyzo.verifier.util.PrintUtil;
import co.nyzo.verifier.util.UpdateUtil;

import java.nio.ByteBuffer;
import java.util.*;

public class NodeManager {

    private static final int maximumNumberOfSeedVerifiers = 10;

    // TODO: work out the details of multiple verifiers at a single IP address

    private static final List<Node> nodePool = new ArrayList<>();
    private static final Map<Integer, Node> ipAddressToNodeMap = new HashMap<>();
    private static final Map<ByteBuffer, Node> identifierToNodeMap = new HashMap<>();

    private static final int consecutiveFailuresBeforeRemoval = 8;
    private static final Map<String, Integer> nodeConnectionFailureMap = new HashMap<>();

    // full node: a node that accepts incoming connections
    // client node: a node that does not accept incoming connections

    // When a node joins the network, it broadcasts a node-has-joined message directly to every full node in the mesh.
    // The node-has-joined message contains only the identifier that the verifier wants to associate with the node.
    // The full node stores the timestamp, the IP address, and the identifier.

    public static void updateNode(byte[] identifier, byte[] ipAddress, int port, boolean fullNode) {

        updateNode(identifier, ipAddress, port, fullNode, 0);
    }

    public static synchronized void updateNode(byte[] identifier, byte[] ipAddress, int port, boolean fullNode,
                                               long queueTimestamp) {

        System.out.println("adding node " + PrintUtil.compactPrintByteArray(identifier) + ", " +
                IpUtil.addressAsString(ipAddress));

        if (identifier != null && identifier.length == FieldByteSize.identifier && ipAddress != null &&
                ipAddress.length == FieldByteSize.ipAddress) {

            ByteBuffer identifierByteBuffer = ByteBuffer.wrap(identifier);

            // Get the existing node from the map.
            int ipAddressAsInt = IpUtil.addressAsInt(ipAddress);
            Node existingNodeForIp = ipAddressToNodeMap.get(ipAddressAsInt);
            Node existingNodeForIdentifier = identifierToNodeMap.get(identifierByteBuffer);

            // To enforce the requirements that each IP may be in the queue only once and each identifier may be in the
            // queue only once:
            // (1) If no node was returned for either lookup, create a new node.
            // (2) If a node was returned for IP but not identifier, update the identifier and port with the IP.
            // (3) If a node was returned for identifier but not IP, update the IP and port with the identifier.
            // (4) If the node returned for both was the same, update the port.
            // (5) If a different node was returned for each, remove the lower-ranked node and update the other.
            if (existingNodeForIp == null && existingNodeForIdentifier == null) {
                System.out.println("adding new node for " + PrintUtil.compactPrintByteArray(identifier) + ", " +
                        IpUtil.addressAsString(ipAddress));

                Node newNode = new Node(identifier, ipAddress, port, fullNode);
                nodePool.add(newNode);
                ipAddressToNodeMap.put(ipAddressAsInt, newNode);
                identifierToNodeMap.put(identifierByteBuffer, newNode);

                // A timestamp is only provided when fetching a node list from another node. Those should already be
                // deduped; if they are not, then simply choosing the first timestamp encountered for each IP and
                // identifier is a reasonable way to clean up the data.
                if (queueTimestamp > 0) {
                    newNode.setQueueTimestamp(queueTimestamp);
                }
            } else if (existingNodeForIdentifier == null) {
                System.out.println("updating IP for " + PrintUtil.compactPrintByteArray(identifier) + ", " +
                        IpUtil.addressAsString(ipAddress));

                existingNodeForIp.setIdentifier(identifier);
                existingNodeForIp.setPort(port);
                identifierToNodeMap.put(identifierByteBuffer, existingNodeForIp);
            } else if (existingNodeForIp == null) {
                System.out.println("updating identifier for " + PrintUtil.compactPrintByteArray(identifier) + ", " +
                        IpUtil.addressAsString(ipAddress));

                existingNodeForIdentifier.setIpAddress(ipAddress);
                existingNodeForIdentifier.setPort(port);
                ipAddressToNodeMap.put(ipAddressAsInt, existingNodeForIdentifier);
            } else if (existingNodeForIp == existingNodeForIdentifier) {
                System.out.println("updating port for " + PrintUtil.compactPrintByteArray(identifier) + ", " +
                        IpUtil.addressAsString(ipAddress));

                existingNodeForIp.setPort(port);
            } else {  // found two different nodes
                System.out.println("found two different nodes");

                if (existingNodeForIp.getQueueTimestamp() < existingNodeForIdentifier.getQueueTimestamp()) {
                    nodePool.remove(existingNodeForIdentifier);
                    existingNodeForIp.setIdentifier(identifier);
                    existingNodeForIp.setPort(port);
                    identifierToNodeMap.put(identifierByteBuffer, existingNodeForIp);
                } else {
                    nodePool.remove(existingNodeForIp);
                    existingNodeForIdentifier.setIpAddress(ipAddress);
                    existingNodeForIdentifier.setPort(port);
                    ipAddressToNodeMap.put(ipAddressAsInt, existingNodeForIdentifier);
                }
            }
        }
    }

    public static synchronized List<Node> getMesh() {
        return new ArrayList<>(nodePool);
    }

    public static boolean connectedToMesh() {

        // When we request the node list from another node, it will add this node to the list. So, the minimum number
        // of nodes in a proper mesh is two.
        return nodePool.size() > 1;
    }

    public static byte[] identifierForIpAddress(String addressString) {

        byte[] identifier = null;
        try {
            int addressValue = IpUtil.addressAsInt(IpUtil.addressFromString(addressString));
            Node node = ipAddressToNodeMap.get(addressValue);
            if (node != null) {
                identifier = node.getIdentifier();
            }
        } catch (Exception ignored) { }

        return identifier;
    }

    public static void markFailedConnection(String hostNameOrIp, int port) {

        String key = hostNameOrIp + "___" + port;
        Integer count = nodeConnectionFailureMap.get(key);
        if (count == null) {
            count = 1;
        } else {
            count++;
        }

        if (count < consecutiveFailuresBeforeRemoval) {
            nodeConnectionFailureMap.put(key, count);
        } else {
            nodeConnectionFailureMap.remove(key);
            removeNodeFromMesh(hostNameOrIp);
        }
    }

    public static void markSuccessfulConnection(String hostNameOrIp, int port) {

        String key = hostNameOrIp + "___" + port;
        nodeConnectionFailureMap.remove(key);
    }

    private static synchronized void removeNodeFromMesh(String hostNameOrIp) {

        byte[] ipAddress = IpUtil.addressFromString(hostNameOrIp);
        if (ipAddress != null) {
            Integer ipAddressValue = IpUtil.addressAsInt(ipAddress);
            Node node = ipAddressToNodeMap.remove(ipAddressValue);
            if (node != null) {
                nodePool.remove(node);
                identifierToNodeMap.remove(node.getIdentifier());
                System.out.println("removed node at " + hostNameOrIp + " from mesh");
            }
        }
    }
}
