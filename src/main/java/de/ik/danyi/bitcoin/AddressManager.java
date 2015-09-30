package de.ik.danyi.bitcoin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import javax.swing.SwingUtilities;

import org.bitcoinj.core.Sha256Hash;
import org.json.JSONArray;
import org.json.JSONObject;

import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.JdkFutureAdapters;

/**
 * Az adott hálózathoz (teszt vagy fő) megfelelő Bitcoin címek létrehozását,
 * kulcsok mentését és betöltését végzi.
 *
 * Created by Imre Danyi on 2015.04.15..
 */
public class AddressManager {

    // a korábban létrehozott címekhez tartozó elköltetlen kimenetek lekérdezését
    // egy külső fél által biztosított API segítségével végezzük
    final String THIRD_PARTY = "https://chain.so/api/v2/";
    final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private List<Future<HttpResponse>> futures;

    // a létrehozott vagy betöltött címeket tárolja
    private ArrayList<BitcoinAddress> addressList;

    public AddressManager(){
        addressList = new ArrayList<BitcoinAddress>();
        futures = new ArrayList<Future<HttpResponse>>();
    }

    /**
     * A WIF formátumban tárolt privát kulcsokhoz előállítjuk a megfelelő
     * nyilvános kulcsokat, majd abből a megfelelő Bitcoin címet.
     *
     * @param walletFile
     */
    public void loadFromFile(File walletFile){

        if(walletFile == null){
            return;
        }

        int numOfLoadedKeys = 0;

        try {
            BufferedReader br = new BufferedReader(new FileReader(walletFile));
            String line = null;

            while ((line = br.readLine()) != null){
                String[] strArray = line.split(" ");
                String wif = strArray[0].trim();
                String time = strArray[1].trim();

                byte[] privKey = BitcoinAddress.privKeyFromWIF(wif);

                if(privKey != null){
                    final BitcoinAddress address = new BitcoinAddress(privKey);

                    if(!getAddressList().contains(address)){
                        getAddressList().add(address);

                        // létrehozási idő beállítása
                        DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                        try {
                            LocalDate localDate = LocalDate.parse(time, formatter);
                            Date date = Date.valueOf(localDate);

                            address.getEcKey().setCreationTimeSeconds(date.getTime() / 1000);
                        } catch (DateTimeParseException e){
                            address.getEcKey().setCreationTimeSeconds(0);
                        } catch (IllegalArgumentException e){
                            address.getEcKey().setCreationTimeSeconds(0);
                        }

                        // címlista frissítése
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                App.ui.addAddrToList(address.getAddress());
                            }
                        });
                        numOfLoadedKeys++;

                        // a címhez kapcsolódó elkölthető kimenetek lekérdezése
                        // egy harmadik féltől származó API segítségével
                        querySpendableOuts(address.getAddress());
                    }
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }

        final int finalNumOfLoadedKeys = numOfLoadedKeys;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                App.ui.showMessageForUser(String.format("%d privát kulcs került betöltésre.", finalNumOfLoadedKeys),
                        "", false);
            }
        });
    }

    /**
     * Az általunk birtokolt privát kulcsokat WIF formátumban írjuk ki egy tárcafájlba.
     * Mivel a megfelelő nyilvános kulcsok a privát kulcsokból az elliptikus görbén történő
     * szorzással visszanyerhetőek, elegendő csak a privát kulcsokat eltárolni.
     *
     * Fontos megjegyezni, hogy a tárcafájl tartalma nem kerül titkosításra, így az bárki
     * számára olvasható.
     *
     * A privát kulcsok tárolása a tárcafájlban az alábbi formában történik:
     * (https://multibit.org/en/help/v0.5/help_exportingPrivateKeys.html)
     *
     * <Base58 encoded private key>[<whitespace>[<key createdAt>]]
     *
     * The Base58 encoded private keys are the same format as
     * produced by the Satoshi client/ sipa dumpprivkey utility.
     *
     * Key createdAt is in UTC format as specified by ISO 8601
     * e.g: 2011-12-31T16:42:00Z . The century, 'T' and 'Z' are mandatory
     *
     * @param walletFile
     */
    public void savePrivKeys(File walletFile) {

        int numOfSavedKeys = 0;

        try {

            if (!walletFile.exists()) {
                walletFile.createNewFile();
            }

            PrintStream fileStream = new PrintStream(walletFile);

            for (BitcoinAddress address : getAddressList()) {
                String wif = address.convertToWIF();
                String creationTime = Utils.convertToISO8601(address.getEcKey().getCreationTimeSeconds() * 1000);

                fileStream.println(wif + " " + creationTime);
                numOfSavedKeys++;
            }

            fileStream.flush();
            fileStream.close();

        } catch (FileNotFoundException e){
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }

        final int finalNumOfSavedKeys = numOfSavedKeys;
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                App.ui.showMessageForUser(String.format("%d privát kulcs került kimentésre.", finalNumOfSavedKeys), "", false);
            }
        });
    }

    /**
     * Eltávolítja a megadott címet a címlistából.
     * @param address
     * @return
     */
    public boolean removeAddress(final String address){
        boolean removed = addressList.remove(getAddress(address));

        if(removed){
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    App.ui.removeAddrFromList(address);
                }
            });
        }

        return removed;
    }

    /**
     * Megadott számú új Bitcoin címet hoz létre.
     *
     * @param num
     * @return
     */
    public List<BitcoinAddress> createAddress(int num){

        List<BitcoinAddress> createdAddresses = new ArrayList<BitcoinAddress>();

        for(int i=0; i<num; ++i){

            // új Bitcoin cím generálása
            final BitcoinAddress address = new BitcoinAddress();

            // létrehozási idő hozzáadása
            address.getEcKey().setCreationTimeSeconds(System.currentTimeMillis() / 1000);

            addressList.add(address);
            createdAddresses.add(address);

            // az újonnan létrehozott Bitcoin cím hozzáadása a címlistához
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    App.ui.addAddrToList(address.getAddress());
                }
            });

            System.out.println(address);
        }

        return createdAddresses;
    }

    public List<BitcoinAddress> getAddressList(){
        return addressList;
    }

    public BitcoinAddress getAddress(String address){
        for(BitcoinAddress addr: addressList){
            if(addr.getAddress().equalsIgnoreCase(address)){
                return addr;
            }
        }

        return null;
    }


    /**
     * Mivel a program nem rendelkezik a blokklánc teljes példányával, így a program indítása előtt
     * már birtokolt (majd "betöltött") címekhez tartozó elköltetlen kimeneteket egy harmadik fél által
     * biztosított API segítségével tudjuk lekérdezni.
     *
     * A függvény tartalma tehát API függő!
     *
     * @param address
     * @return
     */
    public void querySpendableOuts(final String address){

        // elkölthető kimeneteket tartalmazó tranzakciók lekérdezése
        GenericUrl url = new GenericUrl(THIRD_PARTY);
        url.appendRawPath("get_tx_unspent/BTCTEST/" + address);

        try {
            HttpRequest request = HTTP_TRANSPORT.createRequestFactory().buildGetRequest(url);
            Future<HttpResponse> future = request.executeAsync();

            Futures.addCallback(JdkFutureAdapters.listenInPoolThread(future), new FutureCallback<HttpResponse>() {
                @Override
                public void onSuccess(HttpResponse httpResponse) {

                    try {

                        /* A tartalmazó tranzakció tartalmát az ellenőrzés végett lekérdezzük.
                        Válasz üzenet példa:
                        {
                            "status" : "success",
                            "data" : {
                                "network" : "BTCTEST",
                                "address" : "mmYXdcfPemNtwkrkkxVyjhfWomGaL3Tnde",
                                "txs" : [
                                    {
                                    "txid" : "0a1b3170d7d288176437b8b423ebc348e479fc0ef792ef88fb6a40b9faf2068d",
                                        "output_no" : 1,
                                        "script_asm" : "OP_DUP OP_HASH160 421cffcc9df756be1b7b7e80a45928bb636a2e6b OP_EQUALVERIFY OP_CHECKSIG",
                                        "script_hex" : "76a914421cffcc9df756be1b7b7e80a45928bb636a2e6b88ac",
                                        "value" : "0.05000000",
                                        "confirmations" : 48,
                                        "time" : 1430866618
                                    }
                                ]
                            }
                        }
                        */

                        JSONObject obj = new JSONObject(respToString(httpResponse));
                        JSONObject data = obj.getJSONObject("data");
                        JSONArray txs = data.getJSONArray("txs");

                        for(int i=0; i<txs.length(); ++i){
                            String txHashStr = String.valueOf(txs.getJSONObject(i).get("txid"));
                            Sha256Hash txHash = new Sha256Hash(txHashStr);

                            TransactionManager.OutInfo newOutInfo = new TransactionManager.OutInfo(txHash, address);
                            for(TransactionManager.OutInfo outInfo: TransactionManager.getRelevantOuts()){
                                if(outInfo.getAddress().equals(newOutInfo.getAddress())){
                                    continue;
                                }
                            }
                            TransactionManager.getRelevantOuts().add(newOutInfo);


                            // a beágyazottság vizsgálata végett a tranzakciót tartalmazó blokk
                            // tartalmát is lekérdezzük
                            GenericUrl url = new GenericUrl(THIRD_PARTY);
                            url.appendRawPath("get_tx/BTCTEST/" + txHash);

                            HttpRequest request = HTTP_TRANSPORT.createRequestFactory().buildGetRequest(url);

                            Future<HttpResponse> future = request.executeAsync();

                            Futures.addCallback(JdkFutureAdapters.listenInPoolThread(future), new FutureCallback<HttpResponse>() {
                                @Override
                                public void onSuccess(HttpResponse httpResponse) {

                                    /*
                                    Válasz üzenet példa:
                                    {
                                        "status": "success",
                                        "data": {
                                            "txid": "6f47f0b2e1ec762698a9b62fa23b98881b03d052c9d8cb1d16bb0b04eb3b7c5b",
                                            "blockhash": "326b27664910f299e26d4bcbc087c88a45830249baf8a429158127ac8efc711d",
                                            "confirmations": 46336,
                                            "time": 1398122840,
                                            "inputs": [ ... ],
                                            "outputs": [ ... ],
                                            "tx_hex": "...",
                                            "size": 1848,
                                            "version": 1,
                                            "locktime": 0
                                        }
                                    }
                                    */

                                    JSONObject obj = new JSONObject(respToString(httpResponse));
                                    JSONObject data = obj.getJSONObject("data");
                                    String blockHashStr = String.valueOf(data.get("blockhash"));
                                    Sha256Hash blockHash = new Sha256Hash(blockHashStr);
                                    App.bm.addBlockHash(blockHash);
                                }

                                @Override
                                public void onFailure(Throwable throwable) {

                                }
                            });
                        }

                    } catch (IOException e){
                        e.printStackTrace();
                    }

                }

                @Override
                public void onFailure(Throwable throwable) {

                }
            });

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * A lekérésre kapott választ a további elemzéshez szöveggé alakítja.
     * @param resp
     * @return
     */
    private String respToString(HttpResponse resp) {
        String respStr = null;

        try {
            InputStream inputStream  = resp.getContent();

            if(inputStream != null){
                BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
                StringBuilder sb = new StringBuilder();
                String line;

                while ((line = br.readLine()) != null){
                    sb.append(line);
                }

                br.close();

                respStr = sb.toString();
            }

        } catch (IOException e) {
            System.out.println(e.getMessage());
        }

        return respStr;
    }
}
