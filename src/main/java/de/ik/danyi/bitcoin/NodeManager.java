package de.ik.danyi.bitcoin;

import org.bitcoinj.core.*;

import javax.swing.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.*;

/**
 * Vezérli a csomópontok között a lekéréseket, amelyek tranzakcióra, blokkra, vagy
 * blokkfejekre vonatkozhatnak.
 *
 * Meghatározott számú csomóponthoz kapcsolódunk; ha valamelyikkel megszakad a kapcsolat,
 * újhoz csatlakozunk.
 *
 * Created by Imre Danyi on 2015.04.15..
 */
public class NodeManager implements Runnable{

    private final Thread thread;
    private boolean isRunning;

    private int port;
    private int maxNodes;
    private List<Node> nodes;

    // a kapcsolódott csomópontoktól újabb elérhető csomópontok címeit kapjuk meg, valamint
    // előre rögzített DNS szerverektől nslookup révén szerzett IP címeket tárolja
    private Set<PeerAddress> availableAddresses;

    // a lekérésekre szánt maximális válaszidő
    private long timeLimit;

    public NodeManager(int maxNodes, int timeLimit){

        isRunning = true;

        nodes = new ArrayList<Node>();
        availableAddresses = new HashSet<PeerAddress>();

        this.maxNodes = maxNodes;
        this.timeLimit = timeLimit;

        // a Bitcoin választott hálózatában (teszt vagy fő) használatos port (teszthálózat esetén a 18333-as)
        port = App.netParams.getPort();

        addressesFromDNS();

        thread = new Thread(this, "NodeManager");
        thread.start();
    }

    public Set<PeerAddress> getAvailableAddresses() {
        return availableAddresses;
    }

    /**
     * Csomópont címek lekérése DNS szerverektől a hálózathoz történő első kapcsolódáskor,
     * valamint ha az elérhető címek listája kiürült, a DNS szerverektől újra lekérdezzük
     * a nyilvántartott IP címeket.
     */
    private void addressesFromDNS(){

        // elérhető DNS címek listája
        String[] dnsSeeds = App.netParams.getDnsSeeds();

        try {
            for(String dnsSeed: dnsSeeds){
                InetAddress[] addrs = InetAddress.getAllByName(dnsSeed);

                for(InetAddress addr: addrs){

                    PeerAddress peerAddress = new PeerAddress(addr, port);
                    availableAddresses.add(peerAddress);
                }
            }

        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        // az első csomóponthoz csatlakozás
        connectANewNode();
    }

    // ha megtörtént a kézfogás
    public int regNode(Node node) {
        nodes.add(node);
        System.out.println(String.format("Új csomópont: " + node.getSocketAddress()));
        System.out.println("Összes csomópont: " + nodes.size());
        return nodes.size();
    }


    public void removeNode(Node node){
        Iterator<Node> iterator = nodes.iterator();
        while (iterator.hasNext()){
            if(iterator.next().getAddress().equals(node.getAddress())){
                System.out.println("Csomópont törölve: " + node.getSocketAddress());
                iterator.remove();
                break;
            }
        }
        System.out.println("Összes csomópont: " + nodes.size());
    }

    /**
     * Az általunk engedélyezett csomópontok száma.
     * @return
     */
    public int getMaxNodes() {
        return maxNodes;
    }

    /**
     * Az aktuálisan csatlakozott csomópontokat számolja meg.
     * @return
     */
    public int countConnectedNodes(){
        int num = 0;

        for(Node node: nodes){
            if(node.isConnected()){
                ++num;
            }
        }

        return num;
    }

    /**
     * IGAZ értékkel tér vissza, ha minden aktuális csomóponttal szinkronizáltuk
     * a blokkláncunkat.
     *
     * @return
     */
    private boolean syncedWithAllNode(){
        int syncedNodes = 0;

        for(Node node: getNodes()){
            if(node.isConnected() && node.isSyncIsFinished()){
                syncedNodes++;
            }
        }

        if(syncedNodes == countConnectedNodes()){
            return true;
        }else{
            return false;
        }
    }


    /**
     * A kapcsolódó csomópontok további csomópontok címeit küldhetik el nekünk,
     * amelyet későbbi kapcsolódás esetén használunk fel.
     *
     * @param addressList
     */
    public void regAvailableNodes(List<PeerAddress> addressList){
        if(availableAddresses == null){
            availableAddresses = new HashSet<PeerAddress>();
        }

        for(PeerAddress addr: addressList){
            availableAddresses.add(addr);
        }
    }

    /**
     * A következő elérhető csomóponthoz kapcsolódunk.
     */
    public void connectANewNode(){

        if(countConnectedNodes() >= getMaxNodes()) {
            return;
        }

        // ha kiürült az elérhető csomópontok listája, a DNS szerverektől
        // kérünk le ismét elérhető címeket
        if(availableAddresses.size() == 0){
            addressesFromDNS();
            return;
        }

        Iterator<PeerAddress> iterator = availableAddresses.iterator();
        if(iterator.hasNext()){
            PeerAddress addr = iterator.next();

            // az aktuálisan kivett címet töröljük az elérhetőek közül
            availableAddresses.remove(addr);

            InetSocketAddress socketAddress = new InetSocketAddress(addr.getAddr(), port);
            new Node(App.netParams, socketAddress);
        }
    }

    /**
     * Üzenet küldése az összes kapcsolódott csomópontnak.
     *
     * @param message
     */
    public void sendMsgToAllNodes(Message message){

        if(message != null){
            for(Node node: nodes){
                node.sendMessage(message);
            }
        }

    }

    public synchronized List<Node> getNodes(){
        return nodes;
    }

    @Override
    public void run() {

        while (isRunning) {

            for(int i=0; i<getNodes().size(); ++i){

                Node node = null;
                try {
                    node = getNodes().get(i);
                } catch (ConcurrentModificationException e){
                    System.out.println("Konkurens hozzáférési hiba.");
                    e.printStackTrace();
                }

                // ha már megtörtént a kézfogás
                if(node != null && !node.waitForHandshake){

                    Utils.Pending pending = node.getPending();

                    // ha van függőben lévő lekérés
                    if(pending != null){

                        // ha a lekérésre szánt idő lejárt
                        if(pending.isTimeout(timeLimit)){

                            System.out.println("A lekérésre szánt idő lejárt.");
                            boolean b = false;

                            switch (pending.getType()){
                                case Utils.Pending.TRANSACTION:
                                    b = TransactionManager.txHashes.queryIsFailed(pending.getHash());
                                    break;
                                case Utils.Pending.BLOCK:
                                    b = App.bm.blockHashes.queryIsFailed(pending.getHash());
                                    break;
                                case Utils.Pending.HEADERS:
                                    // üres
                                    break;
                            }

                            node.removePending();

                            // ha már nem tudunk a csomóponttól lekérdezni
                            if(!node.isConnected()){
                                removeNode(node);
                            }
                        }
                    }

                    // nincs függőben lévő lekérés, így elindítunk egyet (ha szükséges)
                    else if(node.isConnected()){

                        // először a hiányzó blokkfejlécek letöltésével foglalkozunk

                        // ha a blokklánc még nincs szinkronizálva a csomópontokkal
                        if(!App.bm.isChainSynced()){

                            // ha még van lekérhető blokkfej a csomóponttól
                            if(!node.isSyncIsFinished()) {

                                SwingUtilities.invokeLater(new Runnable() {
                                    @Override
                                    public void run() {
                                        App.ui.printSyncStatus(
                                                String.format("Blokklánc szinkronizálása: %d blokkfej letöltve.",
                                                App.bm.blockChain.getBestChainHeight()));
                                    }
                                });

                                int nodeBestHeight = (int)node.getBestHeight();
                                // az adott csomóponttal szinkronizáltuk a blokkláncunkat
                                if(nodeBestHeight <= App.bm.blockChain.getBestChainHeight()){
                                    node.syncIsFinished();
                                    continue;
                                }

                                // hiányzó blokkfejek lekérése
                                query(node, Utils.Pending.HEADERS);
                            }

                            // ha minden csomóponttal szinkronizáltuk a blokkláncunkat
                            else if(syncedWithAllNode()){
                                App.bm.chainIsSynced();
                                System.out.println("A blokklánc szikronizálásra került.");

                                // UI frissítése
                                SwingUtilities.invokeLater(new Runnable() {
                                    @Override
                                    public void run() {
                                        App.ui.printSyncStatus(String.format("Blokklánc szinkronizálva.",
                                                App.bm.blockChain.getBestChainHeight()));
                                    }
                                });
                            }
                        }

                        // amíg a blokklánc nem kerül szinkronizálásra a csomópontokkal, nem kérjük
                        // le az időközben a csomópontoktól kapott tranzakciók, blokkok tartalmát
                        else if(!TransactionManager.txHashes.isDone()){
                            query(node, Utils.Pending.TRANSACTION);
                        }

                        // majd a kapott blokkok tartalmát kérjük le
                        else if(!App.bm.blockHashes.isDone()){
                            query(node, Utils.Pending.BLOCK);
                        }

                    }
                    else if(!node.isConnected()){
                        removeNode(node);
                    }
                }

            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Tranzakció vagy blokk tartalmát, vagy a hiányzó blokkfejléceket kér le a csomóponttól.
     * @param type
     */
    private void query(Node node, int type){
        Message getMessage = null;
        Sha256Hash hash = null;

        if(type == Utils.Pending.TRANSACTION){
            GetDataMessage getDataMessage = new GetDataMessage(App.netParams);
            hash = TransactionManager.txHashes.getNextHash();
            getDataMessage.addTransaction(hash);
            getMessage = getDataMessage;
        }
        else if(type == Utils.Pending.BLOCK){
            GetDataMessage getDataMessage = new GetDataMessage(App.netParams);
            hash = App.bm.blockHashes.getNextHash();
            getDataMessage.addBlock(hash);
            getMessage = getDataMessage;
        }
        else if(type == Utils.Pending.HEADERS){
            GetHeadersMessage getHeaders = App.bm.getMissingBlockHeaders();
            hash = App.bm.blockChain.getChainHead().getHeader().getHash();
            getMessage = getHeaders;
        }

        // üzenet elküldése
        if(hash != null && getMessage != null){
            node.setPending(new Utils.Pending(hash, type));
            sendMessageTo(getMessage, node);
        }
    }

    /**
     * Üzenet küldése meghatározott csomópontnak.
     *
     * @param message
     * @param node
     */
    private void sendMessageTo(Message message, Node node){
        if(node.isConnected()){
            node.sendMessage(message);
        }else{
            System.out.println(String.format("%s már nem tudunk üzenetet küldeni.", node.getSocketAddress()));
        }
    }
}
