package de.ik.danyi.bitcoin;

import org.bitcoinj.core.*;
import org.bitcoinj.net.NioClient;

import javax.swing.*;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Created by Imre Danyi on 2015.04.19..
 */
public class Node extends PeerSocketHandler {

    private NioClient client;
    private InetSocketAddress socketAddress;

    // a csomóponttal még kapcsolatban vagyunk-e
    private boolean connected = false;

    // a csomópont által birtokolt blokklánc hossza
    private long bestHeight = 0;

    // a függőben lévő lekérés nyilvántartásához
    private de.ik.danyi.bitcoin.Utils.Pending pending = null;

    // a blokkfejlécek szinkronizálása ezzel a csomóponttal megtörtént
    private boolean syncIsFinished = false;

    // megtörtént-e már a kézfogás
    public boolean waitForHandshake = true;

    public Node(NetworkParameters params, InetSocketAddress socketAddress) {
        super(params, socketAddress);

        try {
            client = new NioClient(socketAddress, this, 10000);
            this.socketAddress = socketAddress;

        } catch (ConnectException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public boolean isConnected(){
        return connected;
    }

    /**
     * Rendelkezünk-e mindazon blokkfejekkel, amelyekkel ezen csomópont is.
     */
    public void syncIsFinished(){
        syncIsFinished = true;
    }

    public boolean isSyncIsFinished(){
        return syncIsFinished;
    }

    public InetSocketAddress getSocketAddress() {
        return socketAddress;
    }

    public long getBestHeight(){
        return bestHeight;
    }

    /**
     * A csomópontoktól érkező üzenetek itt kerülnek feldolgozásra.
     * @param message
     * @throws Exception
     */
    @Override
    protected void processMessage(Message message) throws Exception {

        // a kézfogás sikeresen megtörtént
        if (message instanceof VersionAck) {
            waitForHandshake = false;
            System.out.println(String.format("A kézfogás megtörtént. (%s)", socketAddress));

            App.nm.regNode(this);
            App.nm.connectANewNode();
        }
        else if (message instanceof VersionMessage){
            waitForHandshake = false;
            VersionMessage versionMessage = (VersionMessage) message;

            bestHeight = versionMessage.bestHeight;
        }
        // a csomópont további csomópontok listáját küldte el
        else if(message instanceof AddressMessage){
            AddressMessage addressMessage = (AddressMessage)message;
            App.nm.regAvailableNodes(addressMessage.getAddresses());
        }
        else if(message instanceof Ping){
            Ping ping = (Ping)message;

            if (ping.hasNonce()) {
                long nonce = ping.getNonce();
                // a csomóponttal érkező ping üzenetre egy pong üzenettel válaszolunk
                Pong pong = new Pong(nonce);
                sendMessage(pong);
            }
        }
        else if(message instanceof HeadersMessage){
            HeadersMessage headersMessage = (HeadersMessage)message;

            // a csomópont függő kérésének vizsgálatához csak az első blokkra van szükség
            List<Block> headers = headersMessage.getBlockHeaders();
            Block first = headers.get(0);

            // a csomóponttól lekért tranzakció megérkezett
            if(pending != null && pending.isReceived(first.getPrevBlockHash())){
                removePending();
            }

            // a csomóponttól kapott blokkfejeket rögzítjük
            App.bm.regMissingBlockHeaders(headers);
        }
        else if(message instanceof Transaction){
            Transaction transaction = (Transaction)message;

            // a csomóponttól lekért tranzakció megérkezett
            if(pending != null && pending.isReceived(transaction.getHash())){
                TransactionManager.txHashes.queryIsDone(transaction.getHash());
                removePending();
            }

            // a kapott tranzakciót megvizsgáljuk, hogy számunkra releváns-e
            TransactionManager.addTxForFilter(transaction);
        }
        else if(message instanceof Block) {
            final Block block = (Block)message;

            // a csomóponttól lekért blokk megérkezett
            if(pending != null && pending.isReceived(block.getHash())){
                App.bm.blockHashes.queryIsDone(block.getHash());
                removePending();
            }

            // minden kapott blokkot felhasználunk az elkölthető és az elköltött kimenetek
            // beágyazottságának vizsgálatához
            TransactionManager.addBlockForConfirmation(block);

            // a beérkező blokkot hozzáadjuk a blokklánchoz
            boolean isAdded = App.bm.addBlock(block);
            System.out.println(String.format("%s blokk hozzáadva a blokklánchoz: %b",
                    block.getHashAsString(), isAdded));

            // ha ez nem sikerült, az azt jelentheti, hogy a blokkláncunk folyamatossága megszakadt;
            // valószínűleg egy kapcsolatmegszakadás és kapcsolatfelvétel közötti átmeneti időben létrejött
            // egy új blokk, amely nem került beépítésre, ezért a blokkláncot újból szinkronizálni kell
            if(!isAdded){

                // ha a beérkező blokk nem a blokkláncunk következő blokkja
                Sha256Hash chainHeadHash = App.bm.getBlockHash(App.bm.blockChain.getChainHead());
                if(!block.getPrevBlockHash().equals(chainHeadHash)) {

                    System.out.println(String.format(
                            "A blokkláncot újból szinkronizálnunk kell. Blockchain: %d, BestHeaders: %d",
                            App.bm.blockChain.getBestChainHeight(), App.bm.bestHeaders.size()-1));

                    GetHeadersMessage getHeaders = App.bm.getMissingBlockHeaders();
                    if (getHeaders != null) {
                        sendMessage(getHeaders);
                    }
                }
            }
        }
        // a hálózaton szétterjedő tranzakciók, blokkok hash értékeit tartalmazó üzenet
        else if (message instanceof InventoryMessage) {
            InventoryMessage msg = (InventoryMessage) message;

            List<InventoryItem> items = msg.getItems();

            for (InventoryItem item : items) {

                Sha256Hash itemHash = item.hash;

                if(item.type.equals(InventoryItem.Type.Transaction)){
                    TransactionManager.addTxHash(itemHash);
                }
                else if(item.type.equals(InventoryItem.Type.Block)){
                    App.bm.addBlockHash(itemHash);
                }
            }
        }
        // visszautasítás esetén beérkező üzenet
        else if(message instanceof RejectMessage){
            RejectMessage msg = (RejectMessage)message;
            final String reason = msg.getReasonString();
            final RejectMessage.RejectCode reasonCode = msg.getReasonCode();

            // ha egy általunk létrehozott tranzakció nem került a csomópont által elfogadásra
            if(msg.getRejectedMessage().equals("tx")){
                final Sha256Hash rejectedTxHash = msg.getRejectedObjectHash();

                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        App.ui.showMessageForUser(
                                String.format("Tranzakció: %s\nvisszautasításra került.\nHiba oka: %s: %s",
                                        rejectedTxHash, reasonCode.name(), reason),
                                "Visszautasított tranzakció!", true);
                    }
                });

                // ha általunk létrehozott tranzakció került elutasításra, az abban
                // elköltött kimenetek nem kerültek elköltésre
                TransactionManager.resetSpentOuts(rejectedTxHash);
            }
        }

    }

    @Override
    public void connectionClosed() {
        System.out.println("A TCP kapcsolat megszakadt (" + socketAddress + ").");

        connected = false;
        App.nm.connectANewNode();
    }

    // a TCP kapcsolat a csomóponttal felépült
    @Override
    public void connectionOpened() {
        System.out.println("A TCP kapcsolat kiépült ("+socketAddress+").");

        connected = true;
        handshake();
    }

    // a csomópontokkal történő kézfogás egy version üzenet elküldésével indul el
    private void handshake() {
        VersionMessage message = new VersionMessage(App.netParams, App.bm.blockChain.getBestChainHeight());
        message.appendToSubVer("Bitcoin tárcaprogram", "0.1", null);
        sendMessage(message);
    }

    public void setPending(Utils.Pending pending){
        this.pending = pending;
    }

    public Utils.Pending getPending(){
        return pending;
    }

    public void removePending(){
        pending = null;
    }
}
