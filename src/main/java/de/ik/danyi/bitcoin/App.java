package de.ik.danyi.bitcoin;

import org.bitcoinj.core.AbstractBlockChainListener;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.params.TestNet3Params;

/**
 * Created by Imre Danyi on 2015.04.14..
 */
public class App extends AbstractBlockChainListener {

    // a teszt hálózat csomópontjait szeretnénk elérni
    public static final NetworkParameters netParams = TestNet3Params.get();

    public static BlockchainManager bm;

    public static NodeManager nm;
    public static AddressManager am;

    public static TransactionManager tm;

    public static UI ui;

    // az elköltéshez szükséges megerősítések száma
    public static int minConfirmation;

    // a tranzakciós díj minimális értéke
    public static final Coin minFee = Coin.parseCoin("0.0001");

    public static void main(String[] args){

        minConfirmation = 1;

        ui = new UI();

        bm = new BlockchainManager();

        am = new AddressManager();
        tm = new TransactionManager();

        // 5 csomóponthoz szándékozunk folyamatosan kapcsolatban lenni
        // a csomópontokhoz intézett kérésekre maximum 10 másodpercet várunk
        nm = new NodeManager(5, 10000);

    }
}
