package de.ik.danyi.bitcoin;

import org.bitcoinj.core.*;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.spongycastle.util.encoders.Hex;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by Imre Danyi on 2015.04.14..
 */

/**
 * Lehetővé teszi az általunk érdekelt tranzakciók kiszűrését, valamint figyeli ezen tranzakciók
 * blokkláncbeli beágyazottságát a befoglaló blokkok mélységének nyomon követésével.
 */
public class TransactionManager implements Runnable{

    private boolean isRunning;
    public final Thread thread;

    // az elkölthető és az elköltött kimeneteket tartalmazza
    private static List<OutInfo> relevantOuts;

    // a számunkra releváns tranzakciókimenetek megerősítéseinek vizsgálata épp folyamatban van-e
    private boolean confirmationIsPending;
    private ConcurrentLinkedQueue<Block> blocksForConfirmation;

    // releváns tranzakció szűrése folyamatban van-e
    private boolean filterIsPending;
    private ConcurrentLinkedQueue<Transaction> txsForFilter;

    // a csomópontoktól kapott tranzakciókat tároljuk el abból a
    // célból, hogy a teljes tranzakció tartalmát lekérjük
    public static Utils.QueryQueue txHashes;

    public TransactionManager(){

        isRunning = true;

        txHashes = new Utils.QueryQueue();

        filterIsPending = false;
        confirmationIsPending = false;

        txsForFilter = new ConcurrentLinkedQueue<Transaction>();
        blocksForConfirmation = new ConcurrentLinkedQueue<Block>();

        relevantOuts = new ArrayList<OutInfo>();

        thread = new Thread(this, "TransactionManager");
        thread.start();
    }

    /**
     * A következő lekérendő tranzakció hash értéke.
     *
     * @return
     */
    public Sha256Hash getNextTxHash(){
        return txHashes.getNextHash();
    }

    /**
     * Újabb tranzakció tartalmát kell majd lekérnünk, hogy megvizsgáljuk, vajon
     * az adott tranzakció számunkra releváns-e.
     *
     * @param txHash
     */
    public static boolean addTxHash(Sha256Hash txHash){
        boolean isAdded =  txHashes.addHash(txHash);
        if(isAdded){
            System.out.println("Új tx: " + txHash + ", hozzáadva: " + isAdded);
        }

        return isAdded;
    }

    public static synchronized List<OutInfo> getRelevantOuts() {
        return relevantOuts;
    }

    /**
     * A csomópontoktól kapott tranzakciók (kimenetek) közül kiszűri az általunk érdekelteket, és
     * megvizsgálja, hogy általunk elkölthető vagy elköltött kimeneteket tartalmaz-e.
     *
     * @param tx
     */
    private void filter(Transaction tx){

        // a tranzakció érvényes-e (nem vizsgálja, hogy a bemenetek valóban nem lettek-e már korábban elköltve)
        try {
            tx.verify();
        } catch (VerificationException e){
            System.out.println(String.format("A kapott tranzakció (%s) nem érvényes.", tx.getHashAsString()));
            e.printStackTrace();
            return;
        }


        // ha a beérkező tranzakció nem általunk létrehozott, de számunkra releváns, azaz általunk elkölthető
        // kimeneteket tartalmaz, megvizsgáljuk, hogy azok korábban már rögzítve lettek-e
        List<TransactionOutput> txOutputs = tx.getOutputs();
        for(TransactionOutput txOut: txOutputs) {

            for (OutInfo outInfo : getRelevantOuts()) {

                if(outInfo.getOutput() == null){
                    continue;
                }

                if (txOut.getParentTransaction().getHash().equals(outInfo.getOutput().getParentTransaction().getHash())) {
                    return;
                }
            }

        }

        // a beérkező tranzakció releváns, és korábban még nem rögzített elkölthető kimeneteket tartalmaz
        for (int i = 0; i < App.am.getAddressList().size(); ++i) {
            BitcoinAddress myBitcoinAddress = App.am.getAddressList().get(i);

            TransactionOutput txOut = getTxOutByAddr(myBitcoinAddress.getAddress(), tx);

            if (txOut != null) {
                getRelevantOuts().add(new OutInfo(txOut, myBitcoinAddress.getAddress()));
                System.out.println(String.format("%s BTC érkezett erre a címre: %s (TX: %s)",
                        txOut.getValue().toPlainString(), myBitcoinAddress.getAddress(), tx.getHashAsString()));
            }
        }
    }

    public static void addTxForFilter(Transaction tx){
        App.tm.txsForFilter.add(tx);
    }

    /**
     * Visszaadja egy tranzakció azon kimenetét, amely az adott Bitcoin címhez van kötve.
     *
     * @param myAddress
     * @param transaction
     * @return
     */
    private TransactionOutput getTxOutByAddr(String myAddress, Transaction transaction){
        List<TransactionOutput> txAllOuts = transaction.getOutputs();

        for(TransactionOutput out: txAllOuts) {
            String outAddress = null;
            try {
                Address address = out.getAddressFromP2PKHScript(App.netParams);

                if(address != null){
                    outAddress = address.toString();
                }
            } catch (ScriptException e){
                System.out.println(e.getMessage());
            }

            if(outAddress == null){
                return null;
            }

            if(myAddress.equalsIgnoreCase(outAddress)){
                return out;
            }
        }

        return null;
    }

    /**
     * Visszaadja a legalább az adott megerősítéssel rendelkező összes kimenetet.
     * @param confirmation
     * @return
     */
    public List<OutInfo> getOutsByConfirmations(int confirmation){
        List<OutInfo> outsByConf = new ArrayList<OutInfo>();

        for(int i=0; i<getRelevantOuts().size(); ++i){
            OutInfo outInfo = getRelevantOuts().get(i);

            if(outInfo.getOutput() != null && outInfo.getOutputConfirmations() >= confirmation){
                outsByConf.add(outInfo);
            }
        }

        return outsByConf;
    }

    /**
     * Visszaadja az adott címhez kapcsolódó összes tranzakciókimenetet.
     * @param selectedAddress
     * @return
     */
    public List<OutInfo> getOutsByAddress(String selectedAddress){

        List<OutInfo> outsByAddr = new ArrayList<OutInfo>();

        for(int i=0; i<getRelevantOuts().size(); ++i){
            OutInfo outInfo = getRelevantOuts().get(i);

            if(outInfo.getOutput() != null && outInfo.getAddress().equals(selectedAddress)){
                outsByAddr.add(outInfo);
            }
        }

        return outsByAddr;
    }

    /**
     * Tranzakciókimenet alapján ad vissza egy OutInfo objektumot.
     * @param output
     * @return
     */
    private OutInfo getOutInfoByOutput(TransactionOutput output){
        for(int i=0; i<getRelevantOuts().size(); ++i){
            OutInfo outInfo = getRelevantOuts().get(i);
            if(outInfo.getOutput().equals(output)){
                return outInfo;
            }
        }

        return null;
    }

    private boolean isConfirmationIsPending(){
        return confirmationIsPending;
    }

    private boolean isFilterIsPending(){
        return filterIsPending;
    }

    private void setFilterIsPending(boolean f){
        filterIsPending = f;
    }

    private void setConfirmationIsPending(boolean p){
        confirmationIsPending = p;
    }

    /**
     * A csomóponttól lekért teljes blokkot használja, végigmegy a kimeneteket tartalmazó tranzakciókon,
     * és frissíti az adott kimenet blokkláncbeli beágyazottságát.
     * @param outInfo
     * @param block
     */
    private void updateDeepInfo(OutInfo outInfo, Block block){

        Sha256Hash blockHash = block.getHash();
        boolean isBlockInBestChain = App.bm.isBlockInBestChain(blockHash);

        // az elkölthető kimenet beágyazottságát vizsgáljuk, majd ha van,
        // az elköltött kimenetét is
        int type = OutInfo.OUTPUT;
        Transaction outInfoTx = null;
        while(true){

            if(type == OutInfo.OUTPUT){
                outInfoTx = outInfo.getOutput().getParentTransaction();
            }
            else if(type == OutInfo.INPUT){
                outInfoTx = outInfo.getInput().getParentTransaction();
            }

            // ha az adott kimenetet tartalmazó tranzakció része a blokknak
            if (getTxFromBlockByTxHash(outInfoTx.getHash(), block) != null) {

                // ha a blokk nem része a fő láncnak, a kimenet megerősítéseit lenullázzuk
                if (!isBlockInBestChain) {
                    outInfo.setConfirmations(type, 0);
                    outInfo.setBlockHash(type, null);
                    System.out.println(String.format("Új %s mélységét lenulláztuk.",
                            outInfoTx.getHash()));
                }
                // ha a blokk része a fő blokkláncnak
                else {
                    int deepInfo = App.bm.getDeepInfo(blockHash);
                    outInfo.setConfirmations(type, deepInfo);
                    outInfo.setBlockHash(type, blockHash);
                    System.out.println(String.format("Új %s új mélysége: %d",
                            outInfoTx.getHash(), deepInfo));
                }
            }
            // ha nem része az adott kimenetet tartalmazó tranzakció a blokknak
            else {

                Sha256Hash outInfoBlockHash = outInfo.getBlockHash(type);

                if (outInfoBlockHash == null) {
                    confirmationIsPending = false;
                    return;
                }

                // ha a kimenet befoglaló blokkja a fő lánc része
                if (App.bm.isBlockInBestChain(outInfoBlockHash)) {

                    int deepInfo = App.bm.getDeepInfo(outInfoBlockHash);
                    outInfo.setConfirmations(type, deepInfo);

                    System.out.println(String.format("Régebbi %s új mélysége: %d",
                            outInfoTx.getHash(), deepInfo));
                }
                // ha nem a fő lánc része, akkor a kimenet jelenleg nincs megerősítve
                else {

                    outInfo.setConfirmations(type, 0);
                    outInfo.setBlockHash(type, null);
                    System.out.println(String.format("Régebbi %s mélységét lenulláztuk.",
                            outInfoTx.getHash()));
                }

            }


            if(type == OutInfo.OUTPUT && outInfo.isSpent()){
                // a következő körben az elköltött kimenet beágyazottságát vizsgáljuk
                type = OutInfo.INPUT;
            }else{
                break;
            }
        }
    }

    /**
     * Ha az adott OutInfo nem tartalmaz kimenetet, azaz "üres", a tartalmazott tranzakció hash értéke
     * alapján az adott blokkból kinyerjük a megfelelő tranzakciót az OutInfo kitöltéséhez.
     *
     * Üres OutInfo-t a lemezről beimportált címek (azok privát kulcsa) esetén hozunk létre.
     *
     * @param outInfo
     * @param block
     */
    private void fillOutInfo(OutInfo outInfo, Block block){

        Sha256Hash outputParentTxHash = outInfo.getOutputParentTransactionHash();

        if(outputParentTxHash != null && outInfo.getOutput() == null){
            Transaction tx = getTxFromBlockByTxHash(outputParentTxHash, block);
            String myOutAddress = outInfo.getAddress();

            if(tx != null){
                TransactionOutput txOut = getTxOutByAddr(myOutAddress, tx);

                if(txOut != null){
                    outInfo.setOutput(txOut);
                    outInfo.outputParentTransactionHash = null;
                }
            }
        }
    }

    /**
     * Az elkölthető és elköltött kimenetek beágyazottságát frissíti.
     * @param block
     */
    public static void addBlockForConfirmation(Block block){
        App.tm.blocksForConfirmation.add(block);
    }

    /**
     * Elutasított tranzakció esetén az elköltött kimenet újból elkölthetővé válik.
     * @param rejectedTxHash
     */
    public static void resetSpentOuts(Sha256Hash rejectedTxHash){

        for(int i=0; i<getRelevantOuts().size(); ++i) {
            OutInfo outInfo = getRelevantOuts().get(i);

            // ha a visszautasított tranzakcióban létrehoztunk magunk számára kimenetet (pl. visszajáró),
            // azt a kimenetet töröljük
            Sha256Hash outputParentTxHash = outInfo.getOutput().getParentTransaction().getHash();
            if(outputParentTxHash.equals(rejectedTxHash)){
                getRelevantOuts().remove(outInfo);
                continue;
            }

            TransactionInput input = outInfo.getInput();
            if(input == null)
                continue;

            Sha256Hash inputParentTxHash = input.getParentTransaction().getHash();
            if(inputParentTxHash.equals(rejectedTxHash)){
                outInfo.getInput().disconnect();
                outInfo.setInput(null);

                System.out.println(String.format("%s BTC visszakerült erre a címre: %s",
                        outInfo.getOutput().getValue().toPlainString(), outInfo.getAddress()));
            }
        }

    }

    /**
     * Megvizsgálja, hogy az adott tranzakció befoglalásra került-e az adott blokkba, és ha igen,
     * a keresett tranzakció tartalmát visszaadja.
     * @param txHash
     * @param block
     * @return
     */
    private Transaction getTxFromBlockByTxHash(Sha256Hash txHash, Block block){

        // a blokk érvényes-e
        try {
            // munkabizonyíték és időbélyeg ellenőrzése, valamint, hogy a blokkfejlécben tárolt
            // Merkle-gyökér megfelel-e a befoglalt tranzakcióknak
            block.verify();

        } catch (VerificationException e){
            System.out.println(String.format("A kapott blokk (%s) nem érvényes.", block.getHashAsString()));
            System.out.println(e.getMessage());

            // ha jelez hibát, többnyire "Block too far in future" hibát jelez, amelynek az az oka,
            // hogy a gépünk rendszerideje nem megfelelő (https://bitcointalk.org/index.php?topic=947502)

            // ilyenkor a blokk tartalmát újból lekérdezzük
            App.bm.blockHashes.queryIsFailed(block.getHash());

            return null;

        }

        // a blokk befoglalt tranzakciói közül megkeressük az általunk keresettet
        List<Transaction> blockTxs = block.getTransactions();
        for(Transaction blockTx: blockTxs){

            if(blockTx.getHash().equals(txHash)){
                return blockTx;
            }

        }

        return null;
    }

    /**
     * Lekéri az adott Bitcoin címhez tartozó egyenleget. Csak azon tranzakciók kimeneteit képes vizsgálni,
     * amelyek a tárcaalkalmazás elindítása óta a csomópontoktól érkeztek.
     *
     * @param myBitcoinAddress
     * @param outConfirms Csak azokat a tranzakciókimeneteket veszi figyelembe, amelyek legalább a kellő számú
     *                    megerősítéssel rendelkeznek.
     * @return
     */
    public Coin getBalanceByAddr(BitcoinAddress myBitcoinAddress, int outConfirms){

        Coin balance = Coin.parseCoin("0");

        for(int i=0; i<getRelevantOuts().size(); ++i){
            OutInfo outInfo = getRelevantOuts().get(i);

            // ha az adott kimenet nem lett elköltve
            if(!outInfo.isSpent()
                    // és legalább egy meghatározott számú megerősítéssel rendelkezik
                    && outInfo.getOutputConfirmations() >= outConfirms
                    // és az adott címhez kapcsolódik
                    && outInfo.getAddress().equals(myBitcoinAddress.getAddress())
                    // és nem "üres" kimenet
                    && outInfo.getOutput() != null){

                balance = Utils.addCoin(balance, outInfo.getOutput().getValue());
            }
        }

        return balance;
    }

    /**
     * A megfelelő megerősítéssel rendelkező kimenetek összegyenlegét adja meg.
     * @param outConfirms
     * @return
     */
    public Coin getBalance(int outConfirms){

        Coin balance = Coin.valueOf(0);

        for(int i=0; i<App.am.getAddressList().size(); ++i){
            BitcoinAddress bitcoinAddress = App.am.getAddressList().get(i);

            balance = Utils.addCoin(balance, getBalanceByAddr(bitcoinAddress, outConfirms));
        }

        return balance;
    }

    /**
     * Tranzakció létrehozása meghatározott Bitcoin mennyiség elköltéséhez. Jelen tárcaprogram
     * csak P2PKH tranzakciót képes létrehozni, amely az esetleges visszajárótól eltekintve csak
     * 1 Bitcoin címnek képes utalni.
     *
     * @param value Elköltendő összeg.
     * @param requiredConfirmation Mennyi megerősítéssel rendelkező tranzakciókimeneteket költsünk el.
     * @return
     */
    public Transaction createTx(String toAddress, Coin value, Coin fee, int requiredConfirmation) {

        Transaction tx = new Transaction(App.netParams);

        // ha az elköltendő összeg túl kicsi, a tranzakció nem kerül elfogadásra
        if(value.isLessThan(tx.MIN_NONDUST_OUTPUT)){
            System.out.println(
                    String.format("Az elkölteni kívánt összeg túl kicsi. A minimálisan küldhető összeg: %s BTC",
                            tx.MIN_NONDUST_OUTPUT.toPlainString()));
            return null;
        }

        // a kimeneteket csökkenő sorrendbe rendezzük, hogy először a legnagyobbak
        // kerüljenek elköltésre
        getRelevantOuts().sort(new Comparator<OutInfo>() {
            @Override
            public int compare(OutInfo o1, OutInfo o2) {

                if (o1.isSpent() || o2.isSpent()) {
                    return 0;
                }

                TransactionOutput out1 = o1.getOutput();
                TransactionOutput out2 = o2.getOutput();

                if (out1.getValue().isLessThan(out2.getValue())) {
                    return 1;
                } else if (out1.getValue().isGreaterThan(out2.getValue())) {
                    return -1;
                }
                return 0;
            }
        });

        // kiválasztjuk az elköltendő kimeneteket
        List<OutInfo> outs = new ArrayList<OutInfo>();
        Coin outValue = Coin.parseCoin("0");

        // addig végezzük a kimenetek kiválasztását, amíg az esetleges tranzakciós díjjal megnövelt
        // elköltendő összeget el nem érjük
        Iterator<OutInfo> iterator = getOutsByConfirmations(requiredConfirmation).iterator();
        while (outValue.getValue() < (value.getValue() + fee.getValue())){
            if(iterator.hasNext()){

                OutInfo outInfo = iterator.next();

                // elköltött vagy "üres" kimenetet nem veszünk figyelembe
                if(outInfo.isSpent() || outInfo.getOutput() == null){
                    continue;
                }

                TransactionOutput nextOut = outInfo.getOutput();
                Coin nextCoin = nextOut.getValue();
                outValue = Utils.addCoin(outValue, nextCoin);
                outs.add(outInfo);

            } else {
                SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        App.ui.showMessageForUser("Nincs elegendő fedezet a kívánt összeg elköltéséhez.",
                                "Nincs elegendő fedezet", false);
                    }
                });
                System.out.println("Nincs elegendő fedezet a kívánt összeg elköltéséhez.");
                return null;
            }
        }
        // rendelkezésre áll a szükséges fedezet

        System.out.println("Elköltendő: " + value.toPlainString());
        System.out.println("Összeválogatott összeg: " + outValue.toPlainString());

        // tranzakció kimenetek hozzáadása a tranzakcióhoz
        OutInfo outForPayee = null;
        OutInfo outForChange = null;
        try {
            // címzettnek létrehozott kimenet
            TransactionOutput outputForPayee = tx.addOutput(value, new Address(App.netParams, toAddress));

            // ha a címzett a mi címünk, a létrehozott kimenetet rögzítjük
            if(App.am.getAddress(toAddress) != null){
                outForPayee = new OutInfo(outputForPayee, toAddress);
            }

            // kiszámoljuk a visszajárót
            Coin change = Utils.subCoin(outValue, value);
            System.out.println("Visszajáró + díj: " + change.toPlainString());
            change = Utils.subCoin(change, fee);
            System.out.println("Visszajáró: " + change.toPlainString());

            // csak akkor kérjük a visszajárót, ha az nagyobb, mint a megengedett legkisebb összeg ("dust kimenet"),
            // különben a tranzakció nem kerülne a többi csomópont által elfogadásra
            if(change.isGreaterThan(Transaction.MIN_NONDUST_OUTPUT)) {

                System.out.println("A visszajáró megfelelő nagyságú.");

                // a visszajárónak egy külön címet és kimenetet hozunk létre, amelyet regisztrálunk
                BitcoinAddress addrForChange = App.am.createAddress(1).get(0);
                TransactionOutput outputForChange =
                        tx.addOutput(change, new Address(App.netParams, addrForChange.getAddress()));
                outForChange = new OutInfo(outputForChange, addrForChange.getAddress());

                System.out.println(String.format("Visszajáró: %s BTC erre a címre: %s",
                        change.toPlainString(), addrForChange.getAddress()));
            }

        } catch (AddressFormatException e) {
            System.out.println("A kedvezményezett cím nem megfelelő formátumú.");
            e.printStackTrace();
        }

        // tranzakció bemenetek hozzáadása a tranzakcióhoz
        for(int i=0; i<outs.size(); ++i){

            OutInfo outInfo = outs.get(i);
            TransactionOutput out = outInfo.getOutput();

            TransactionOutPoint outPoint =
                    new TransactionOutPoint(App.netParams, out.getIndex(), out.getParentTransaction());

            TransactionInput input = new TransactionInput(App.netParams, tx, new byte[]{}, outPoint);

            tx.addInput(input);
            input.connect(out);

            // az általunk tárolt kimenethez egy bemenetet kapcsolunk
            outInfo.setInput(input);
        }


        // a hozzáadott bemenetekhez kapcsolódó zároló szkript(ek) feloldása a megfelelő scriptSig létrehozásával
        for(int i=0; i<tx.getInputs().size(); ++i){
            TransactionInput input = tx.getInput(i);

            TransactionOutput out = input.getConnectedOutput();
            Script scriptPubKey = out.getScriptPubKey();

            // a zároló szkriptben (scriptPubKey) rögzített cím
            Address destinationAddress = scriptPubKey.getToAddress(App.netParams, true);
            BitcoinAddress myAddress = App.am.getAddress(destinationAddress.toString());

            if (myAddress != null) {
                ECKey ecKey = myAddress.getEcKey();

                Sha256Hash hashForSignature = tx.hashForSignature(i, scriptPubKey, Transaction.SigHash.ALL, false);
                ECKey.ECDSASignature ecSig = ecKey.sign(hashForSignature);
                TransactionSignature txSig = new TransactionSignature(ecSig, Transaction.SigHash.ALL, false);

                if (scriptPubKey.isSentToRawPubKey()) {
                    input.setScriptSig(ScriptBuilder.createInputScript(txSig));
                } else if (scriptPubKey.isSentToAddress()) {
                    input.setScriptSig(ScriptBuilder.createInputScript(txSig, ecKey));
                } else {
                    throw new ScriptException("A program nem képes a zároló szkripthez megfelelő aláírást " +
                            "létrehozni:" + scriptPubKey);
                }

                // a feloldó szkript érvényességének ellenőrzése
                try {

                    input.getScriptSig().correctlySpends(tx, i, scriptPubKey);

                } catch (final ScriptException e) {
                    System.out.println("A létrehozott feloldó szkript érvénytelen.");
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            App.ui.showMessageForUser(e.getMessage(), "Érvénytelen feloldó szkript", false);
                        }
                    });
                    e.printStackTrace();
                    return null;
                }


                System.out.println("scriptPubKey: " + Hex.toHexString(scriptPubKey.getProgram()));
                Script scriptSig = tx.getInput(i).getScriptSig();
                System.out.println("ScriptSig: " + Hex.toHexString(scriptSig.getProgram()));
            }
        }

        // a létrehozott tranzakció érvényes-e
        try {
            tx.verify();
        }catch (VerificationException e){
            e.printStackTrace();
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    App.ui.showMessageForUser("A létrehozott tranzakció nem érvényes.", "Érvénytelen tranzakció", false);
                }
            });
        }

        // a tranzakció érvényes
        System.out.println("Létrehozott tranzakció: " + tx.getHash());
        System.out.println("Nyers tranzakció: " + Hex.toHexString(tx.unsafeBitcoinSerialize()));


        // megvizsgáljuk, hogy szükséges-e tranzakciós díjat felajánlani
        boolean isFeeRequired = false;

        // 1. ha a tranzakció bármely kimenete kisebb, mint 0.01 BTC,
        // szükséges a tranzakciós díj
        for(TransactionOutput txOut: tx.getOutputs()){
            if(txOut.getValue().isLessThan(Coin.parseCoin("0.01"))){
                // a kimenet kisebb, ezért szükség van tranzakciós díjra
                isFeeRequired = true;
                break;
            }
        }

        // 2. ha a tranzakció mérete > 1 KB (a referencia kliens alapján 1 KB 1000 bájtnak felel meg),
        // szükséges a tranzakciós díj
        double txSizeInKB = ((double)tx.bitcoinSerialize().length) / 1000;
        if(txSizeInKB > 1){
            isFeeRequired = true;
        }
        //System.out.println("Tranzakció mérete (KB): " + txSizeInKB);

        // 3. ha a létrehozott tranzakció prioritása nem elegendő nagyságú (<=57.600.000), szükség
        // van tranzakciós díjra
        double priority = 0f;
        for(TransactionInput txInput: tx.getInputs()){
            long inputValue = txInput.getValue().getValue();

            TransactionOutput output = txInput.getConnectedOutput();
            OutInfo outInfo = getOutInfoByOutput(output);
            long confirmations = 0;
            if(outInfo != null){
                confirmations = (long)outInfo.getOutputConfirmations();
            }

            priority += (double)(inputValue * confirmations);
        }
        priority /= (txSizeInKB * 1000);

        int result = Double.compare(priority, 57600000);
        if(result < 0 || result == 0){
            isFeeRequired = true;
        }


        // eddigi tranzakciós díj kiszámolása
        // bemenetek összértéke
        long inputValue = outValue.getValue();

        // kimenetek összértéke
        long outputValue = 0;
        for(TransactionOutput output: tx.getOutputs()){
            outputValue += output.getValue().getValue();
        }

        System.out.println("Tranzakciós díj eddig (BTC): " + (inputValue - outputValue));

        // ha szükség van tranzakciós díjra
        double txFee = 0f;
        if(isFeeRequired){
            // a tranzakció méretét a legközelebbi 1000 bájtra kerekítjük fel
            int roundedTxSize = ((tx.bitcoinSerialize().length + 999) / 1000 ) * 1000;

            // a tranzakciós díj: a minimális díj minden 1000 bájt után
            txFee = (double)(roundedTxSize / 1000) * Double.valueOf(App.minFee.toPlainString());

            System.out.println("Szükséges tranzakciós díj (BTC): " + txFee);
        }

        // ha az újraszámolt (szükséges) díj (txFee) nagyobb, mint az előzőleg meghatározott (fee),
        // újra össze kell állítani a tranzakció bemeneteit
        if(Double.compare(txFee, (double)fee.getValue()) > 0){

            // az elköltött-nek jelölt kimeneteket újból elkölthetőnek nyilvánítjuk
            for(OutInfo outInfo: getRelevantOuts()){
                for(TransactionInput txIn: tx.getInputs()){
                    if(outInfo.getOutput().equals(txIn.getConnectedOutput())){
                        outInfo.setInput(null);
                        txIn.disconnect();
                    }
                }
            }

            // a tranzakciót "reseteljük"
            tx.clearInputs();
            tx.clearOutputs();
            tx = null;

            // a visszajárónak létrehozott címet (ha létrehoztunk) töröljük
            if(outForChange != null){
                BitcoinAddress addrForChange = App.am.getAddress(outForChange.getAddress());

                App.am.removeAddress(addrForChange.getAddress());
            }

            // a tranzakciót újból összeállítjuk
            System.out.println("A tranzakciót újból összeállítjuk.");
            tx = createTx(toAddress, value, Coin.parseCoin(String.valueOf(txFee)), requiredConfirmation);
        }
        // ha a tranzakcós díj megfelelő
        else{

            // a tranzakció számunkra releváns kimeneteit eltároljuk
            if(tx != null){
                // saját részünkre elköltött kimenet
                if(outForPayee != null){
                    getRelevantOuts().add(outForPayee);
                }

                // visszajáró
                if(outForChange != null){
                    getRelevantOuts().add(outForChange);
                }
            }
        }

        // az érvényes és megfelelő tranzakciós díjjal ellátott tranzakció küldésre kész
        return tx;
    }


    @Override
    public void run() {
        while (isRunning){

            // a csomópontoktól beérkező tranzakciók közül kiszűri a számunkra relevánsakat
            if(!isFilterIsPending()){
                Iterator<Transaction> iterator = txsForFilter.iterator();
                if(iterator.hasNext()){
                    Transaction tx = iterator.next();
                    setFilterIsPending(true);
                    filter(tx);
                    iterator.remove();
                    setFilterIsPending(false);
                }
            }

            // az elkölthető és elköltött tranzakciókimenetek megerősítéseit frissíti
            if(!isConfirmationIsPending()) {
                Iterator<Block> iterator = blocksForConfirmation.iterator();
                if (iterator.hasNext()) {
                    Block block = iterator.next();
                    setConfirmationIsPending(true);

                    for (int i = 0; i < getRelevantOuts().size(); ++i) {
                        OutInfo outInfo = getRelevantOuts().get(i);

                        // először megpróbáljuk az adott blokk alapján az esetleges "üres" outInfo-t kitölteni,
                        // azaz elkölthető kimenetetet hozzáadni
                        fillOutInfo(outInfo, block);

                        // ha az OutInfo továbbra is üres
                        if(outInfo.getOutput() == null){
                            continue;
                        }

                        // beágyazottsági vizsgálat
                        updateDeepInfo(outInfo, block);
                    }
                    iterator.remove();
                    setConfirmationIsPending(false);
                }
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Egy adott tranzakciókimenetet és az ahhoz kapcsolt esetleges bemenetet (elköltés esetén),
     * a blokkláncbeli beágyazottságot, valamint a kapcsolódó Bitcoin címet tartalmazó osztály.
     */
    static class OutInfo {

        public static final int OUTPUT = 1;
        public static final int INPUT = 2;

        private Sha256Hash outputBlockHash;
        private Sha256Hash inputBlockHash;
        private TransactionOutput output;       // elkölthető kimenet
        private TransactionInput input;         // elköltött kimenet
        private String address;
        private int outputConfirmations;
        private int inputConfirmations;

        private Sha256Hash outputParentTransactionHash;

        public OutInfo(Sha256Hash outputParentTransactionHash, String address){
            this.outputParentTransactionHash = outputParentTransactionHash;
            this.address = address;

            outputConfirmations = 0;
            inputConfirmations = 0;
            outputBlockHash = null;
            inputBlockHash = null;
            output = null;
            input = null;
        }

        public OutInfo(TransactionOutput output, String address){

            this.output = output;
            this.address = address;

            outputConfirmations = 0;
            inputConfirmations = 0;
            outputBlockHash = null;
            inputBlockHash = null;
            input = null;
            outputParentTransactionHash = null;
        }

        public Sha256Hash getOutputParentTransactionHash() {
            return outputParentTransactionHash;
        }

        public String getAddress() {
            return address;
        }

        public TransactionOutput getOutput(){
            return output;
        }

        public TransactionInput getInput() {
            return input;
        }

        public int getOutputConfirmations() {
            return outputConfirmations;
        }

        public int getInputConfirmations() {
            return inputConfirmations;
        }

        public Sha256Hash getOutputBlockHash() {
            return outputBlockHash;
        }

        public Sha256Hash getInputBlockHash() {
            return inputBlockHash;
        }


        public int getConfirmations(int outType){
            if(outType == OutInfo.OUTPUT){
                return outputConfirmations;
            }
            else if(outType == OutInfo.INPUT){
                return inputConfirmations;
            }

            return -1;
        }

        public void setBlockHash(int outType, Sha256Hash blockHash){
            if(outType == OutInfo.OUTPUT){
                outputBlockHash = blockHash;
            }
            else if(outType == OutInfo.INPUT){
                inputBlockHash = blockHash;
            }
        }

        public Sha256Hash getBlockHash(int outType){
            if(outType == OutInfo.OUTPUT){
                return outputBlockHash;
            }
            else if(outType == OutInfo.INPUT){
                return inputBlockHash;
            }

            return null;
        }

        public void setConfirmations(int outType, int confirms){
            if(outType == OutInfo.OUTPUT){
                outputConfirmations = confirms;
            }
            else if(outType == OutInfo.INPUT){
                inputConfirmations = confirms;
            }
        }

        public void setInput(TransactionInput input) {
            this.input = input;

            if(input == null){
                inputBlockHash = null;
                inputConfirmations = 0;
            }
        }

        public void setOutput(TransactionOutput output) {
            this.output = output;
        }

        // a kimenet el lett-e költve
        public boolean isSpent(){
            if(input != null){
                return true;
            }else{
                return false;
            }
        }
    }

}
