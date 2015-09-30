package de.ik.danyi.bitcoin;

import org.bitcoinj.core.*;
import org.bitcoinj.store.BlockStore;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.MemoryBlockStore;

import java.util.*;

/**
 * A blokklánc karbantartását végző osztály. A program indításakor először a hiányzó blokkfejeket
 * kérjük le, ezek meghatározását ez az osztály végzi, valamint az időközben inkonzisztenssé vált
 * lánc hiányzó elemeit azonosítja.
 *
 * A letöltött blokkfejeket a program a memóriában tárolja, ugyanakkor a Bitcoinj könyvtár által
 * alkalmazott MemoryBlockStore csak az utolsó 5000 blokkfejet tartalmazza*. A teljes blokkfej-láncot
 * ezért egy saját tárolóban (bestHeaders) tartjuk nyilván.
 *
 * * "Make MemoryBlockStore store only a rolling window of the last 5000 blocks. Fixes
 * BuildCheckpoints which was trying to store every block header and running out of
 * heap space."
 *
 * Created by Imre Danyi on 2015.04.15..
 */
public class BlockchainManager {

    public BlockStore spvBlockStore;
    public BlockChain blockChain;

    // az aktuális fő lánc összes blokkfejét tartalmazza
    public Map<Sha256Hash, StoredBlock> bestHeaders;
    private StoredBlock lastBestHeader = null;

    // várunk-e valamelyik csomópontra a blokkfejlécek letöltése miatt
    private boolean pending = false;

    private boolean chainIsSynced = false;

    // azon blokkok hash értékei, amelyek tartalmát még nem kértük le
    public Utils.QueryQueue blockHashes;

    public BlockchainManager() {

        blockHashes = new Utils.QueryQueue();

        bestHeaders = new HashMap<Sha256Hash, StoredBlock>();
        Block genesisBlock = App.netParams.getGenesisBlock();
        bestHeaders.put(
                genesisBlock.getHash(),
                new StoredBlock(genesisBlock.cloneAsHeader(), genesisBlock.getWork(), 0));

        try {

            spvBlockStore = new MemoryBlockStore(App.netParams);
            blockChain = new BlockChain(App.netParams, spvBlockStore);

            blockChain.addListener(new BlockChainListener() {

                // minden új blokk hozzáadásakor meghívódik
                public void notifyNewBestBlock(StoredBlock storedBlock) throws VerificationException {

                    Sha256Hash blockHash = storedBlock.getHeader().getHash();

                    // az újonnan beépült blokkfejet hozzáadjuk az összes fejlécet tartalmazó bestHeaders-höz is
                    if(bestHeaders.get(blockHash) == null) {
                        bestHeaders.put(blockHash, storedBlock);
                        lastBestHeader = storedBlock;

                    }
                }

                public void reorganize(StoredBlock splitPoint, List<StoredBlock> oldBlocks, List<StoredBlock> newBlocks)
                        throws VerificationException {

                    // a splitPoint blokk megkeresése (amely blokktól az elágazás történt)
                    if(!bestHeaders.containsKey(getBlockHash(splitPoint))
                            || !bestHeaders.values().containsAll(oldBlocks)){
                        System.out.println("HIBA! A blokkláncunk hiányos!");
                        return;
                    }

                    StoredBlock currentBlock = lastBestHeader;
                    int removed = 0;
                    while (!getBlockHash(currentBlock).equals(getBlockHash(splitPoint))){

                        // az új elágazást alkotó blokkfej eltávolítása
                        bestHeaders.remove(getBlockHash(currentBlock));
                        removed++;

                        currentBlock = bestHeaders.get(getPrevBlockHash(currentBlock));
                    }

                    if(removed != oldBlocks.size()){
                        System.out.println("HIBA! A BestHeaders nincs szinkronban a blokklánccal.");
                        return;
                    }

                    // új blokkfejek beépítése
                    for(StoredBlock newBlock: newBlocks){
                        bestHeaders.put(getBlockHash(newBlock), newBlock);
                        lastBestHeader = newBlock;
                    }

                    // a láncátrendeződés miatt a megváltozott blokkokat (az új és a régieket egyaránt)
                    // újból le kell kérnünk, hogy a releváns kimenetek megerősítéseit (beágyazottságát)
                    // megfelelően frissíthessük
                    for(StoredBlock storedBlock: oldBlocks){
                        addBlockHash(getBlockHash(storedBlock));
                    }

                    for (StoredBlock storedBlock: newBlocks){
                        addBlockHash(getBlockHash(storedBlock));
                    }
                }

                // nem használjuk
                @Override
                public boolean isTransactionRelevant(Transaction transaction) throws ScriptException {
                    return false;
                }

                // nem használjuk
                @Override
                public void receiveFromBlock(Transaction transaction, StoredBlock storedBlock, AbstractBlockChain.NewBlockType newBlockType, int i) throws VerificationException {

                }

                // nem használjuk
                @Override
                public boolean notifyTransactionIsInBlock(Sha256Hash sha256Hash, StoredBlock storedBlock, AbstractBlockChain.NewBlockType newBlockType, int i) throws VerificationException {
                    return false;
                }
            });

        } catch (BlockStoreException e) {
            e.printStackTrace();
        }
    }

    public Sha256Hash getBlockHash(StoredBlock storedBlock){
        return storedBlock.getHeader().getHash();
    }

    private Sha256Hash getPrevBlockHash(StoredBlock storedBlock){
        return storedBlock.getHeader().getPrevBlockHash();
    }

    public void chainIsSynced(){
        chainIsSynced = true;
    }

    public void chainIsNotSynced(){
        chainIsSynced = false;
    }

    public boolean isChainSynced(){
        return chainIsSynced;
    }

    public boolean addBlockHash(Sha256Hash blockHash){
        boolean isAdded =  blockHashes.addHash(blockHash);
        if(isAdded){
            System.out.println("Új block: " + blockHash + ", hozzáadva: " + isAdded);
        }

        return isAdded;
    }

    /**
     * A hiányzó blokkfejek letöltése éppen folyamatban van-e. A következő "adag*"
     * blokkfejet csak az előtte meglévők ismeretében tudjuk meghatározni (lásd getLocator függvény),
     * ezért a blokkfejek lekérését az előzőek beérkezéséig blokkolni kell.
     *
     * *Egy üzenetben ez maximum 2000 blokkfej lehet.
     *
     * @return
     */
    public boolean isPending(){
        return pending;
    }

    public void setPending(boolean b){
        pending = b;
    }

    /**
     * A beérkező lekért blokkot hozzáadjuk a blokklánchoz.
     *
     * @param block
     * @return
     */
    public boolean addBlock(Block block){

        boolean success = false;

        try {
            success = blockChain.add(block);
        } catch (PrunedException e) {
            e.printStackTrace();
        }

        return success;
    }

    /**
     * A blokk benne van-e a fő láncban.
     * Mivel a BestHeaders mindig az aktuális fő láncot képezi,
     * elegendő a blokk meglétét abban vizsgálni.
     *
     * @param blockHash
     * @return
     */
    public boolean isBlockInBestChain(Sha256Hash blockHash){

        if(bestHeaders.containsKey(blockHash)){
            return true;
        }else{
            return false;
        }

    }

    /**
     * Egy adott blokk beágyazottságát kérdezi le.
     * @param blockHash
     * @return
     */
    public int getDeepInfo(Sha256Hash blockHash){
        int deep = -1;

        if(isBlockInBestChain(blockHash)){
            int blockHeight = bestHeaders.get(blockHash).getHeight();
            int bestHeight = blockChain.getBestChainHeight();
            deep = bestHeight - blockHeight + 1;
        }

        return deep;
    }

    /**
     * A hiányzó blokkfejlécek letöltéséhez egy GetHeadersMessage üzenetet állítunk elő.
     *
     * @return
     */
    public GetHeadersMessage getMissingBlockHeaders() {

        // ha épp folyamatban van blokkfejek lekérése, a futtatás nem folytatódik
        if(isPending()){
            return null;
        }
        setPending(true);

        GetHeadersMessage getHeadersMessage = null;
        StoredBlock myChainHead = blockChain.getChainHead();
        Sha256Hash headerHash = myChainHead.getHeader().getHash();

        // a hiányzó blokkfejlécek helyét meghatározzuk
        List<Sha256Hash> locatorHashes = getLocator(headerHash);

        if(locatorHashes != null){
            getHeadersMessage = new GetHeadersMessage(
                    App.netParams,
                    locatorHashes,
                    Sha256Hash.ZERO_HASH);
        }else{
            // nem várunk letöltendő fejlécekre
            setPending(false);
        }

        return getHeadersMessage;
    }

    /**
     * A kérdéses blokktól (ami már megvan) az ősblokkig visszavezető blokkok hash értékeit tartalmazó lista.
     * Forrás: https://en.bitcoin.it/wiki/Protocol_documentation#getblocks
     *
     * @param fromBlockHash
     * @return
     */
    private List<Sha256Hash> getLocator(Sha256Hash fromBlockHash) {

        List<Sha256Hash> hashes = new ArrayList<Sha256Hash>();
        int fromBlockHeight = -1;

        try {
            fromBlockHeight = spvBlockStore.get(fromBlockHash).getHeight();
        } catch (BlockStoreException e) {
            e.printStackTrace();
        }

        if(fromBlockHeight == -1){
            return null;
        }

        // a szükséges blokkok magasságértékeit nyeri ki (a forrásoldal alapján)
        List<Integer> blockHeights = new ArrayList<Integer>();
        int step = 1;
        int start = 0;
        for (int i = fromBlockHeight; i > 0; i -= step, ++start) {
            if(start >= 10){
                step *= 2;
            }

            blockHeights.add(i);
        }
        blockHeights.add(0);

        // a blokkmagasságok alapján végigmegyünk a meglévő blokkfej-láncunkon
        Sha256Hash currentBlockHash = fromBlockHash;
        for (int i = fromBlockHeight; i >= 0; --i) {

            StoredBlock bestHeader = bestHeaders.get(currentBlockHash);

            if(bestHeader == null){
                System.out.println("Nincs ilyen blokk:" + currentBlockHash);

                System.out.println("Hiányzó blokkfejlécek lokalizálása megszakítva. Blockchain: " +
                    blockChain.getBestChainHeight() + " BestHeaders: " + (bestHeaders.size()-1));

                return null;
            }

            int blockHeight = bestHeader.getHeight();

            if(blockHeights.contains(blockHeight)){
                blockHeights.remove(Integer.valueOf(blockHeight));
                hashes.add(currentBlockHash);

                // ha már csak a genezis blokk hash értéke szükséges, a ciklus
                // további futtatására nincs szükség
                if(blockHeights.size() == 1 && blockHeights.get(0) == 0){
                    hashes.add(App.netParams.getGenesisBlock().getHash());
                    break;
                }
            }

            // a következőleg vizsgált blokk a mostani blokk szülő blokkja lesz
            currentBlockHash = bestHeader.getHeader().getPrevBlockHash();
        }

        return hashes;
    }


    /**
     * A csomóponttól kapott blokkfejléceket a blokklánchoz adjuk.
     *
     * @param headers
     */
    public void regMissingBlockHeaders(List<Block> headers){

        Block last = null;

        for(Block block: headers){
            try {

                blockChain.add(block);
                last = block;

                // ha a blokkláncot már szinkronizáltuk, akkor a mostani blokkfejek lekérése az utószinkronizáció része;
                // az esetleges meglévő kimenetek beágyazottságának frissítéséhez a blokkfejek tartalmát valamely
                // csomóponttól lekérjük
                if(isChainSynced()){
                    System.out.println(block.getHash() + " tartalma újból lekérendő.");
                    addBlockHash(block.getHash());
                }


            } catch (PrunedException e) {
                e.printStackTrace();
            }
        }

        System.out.println(String.format("Utolsó blokk: (%s)", last.getHashAsString()));
        setPending(false);
    }
}
