package de.ik.danyi.bitcoin;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Created by Imre Danyi on 2015.04.14..
 */
public class Utils {

    /**
     * Kettős SHA256 hashelést végrehajtó függvény.
     * @param input
     * @return
     */
    public static byte[] doubleSHA256(byte[] input){

        byte[] out = null;

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(input, 0, input.length);
            byte[] first = digest.digest();

            out = digest.digest(first);

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        return out;
    }

    public static String convertToISO8601(long millis){
        TimeZone tz = TimeZone.getTimeZone("UTC");
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'");
        df.setTimeZone(tz);
        return df.format(new Date(millis));
    }

    /**
     * Két Bitcoin érme összeadását végzi.
     * @param c1
     * @param c2
     * @return
     */
    public static Coin addCoin(Coin c1, Coin c2){
        long c1Value = c1.getValue();
        long c2Value = c2.getValue();
        long value = c1Value + c2Value;
        return Coin.valueOf(value);
    }

    /**
     * c1 érméből kivonja a c2 érmét.
     * @param c1
     * @param c2
     * @return
     */
    public static Coin subCoin(Coin c1, Coin c2){
        long c1Value = c1.getValue();
        long c2Value = c2.getValue();
        long value = c1Value-c2Value;
        return Coin.valueOf(value);
    }

    /**
     * Minden csomópont esetében az éppen aktuális (függőben lévő) lekérést regisztráljuk ezzel, egy
     * időkorláttal ellátva. A lekérés vonatkozhat konkrét tranzakció vagy blokk tartalmának,
     * valamint hiányzó blokkfejlécek lekérésére. Blokkfejlécek esetében a lekérendő tartomány
     * kezdő hash értéke kerül eltárolásra.
     */
    public static class Pending{

        public static final int TRANSACTION = 1;
        public static final int BLOCK = 2;
        public static final int HEADERS = 3;

        // a lekérés indulási ideje
        private long startTime;
        private Sha256Hash hash;
        private int type = 0;

        public Pending(Sha256Hash hash, int type){
            this.hash = hash;
            this.type = type;
            startTime = System.currentTimeMillis();
        }

        // az adott csomóponttól érkező válasz erre a lekérésre adott válasz-e
        public boolean isReceived(Sha256Hash receivedHash){
            if(hash.equals(receivedHash)){
                System.out.println(String.format("Beérkező %s: %s",
                        type == TRANSACTION ? "tranzakció" : "blokk",
                        receivedHash.toString()));
                return true;
            }else{
                return false;
            }
        }

        // megvizsgálja, hogy lejárt-e már a lekérésre szánt időkorlát
        // az időkorlátot a NodeManager példányosításával definiáljuk
        public boolean isTimeout(long timeLimit){
            long currentTime = System.currentTimeMillis();

            // lejárt az időkorlát
            if((currentTime - startTime) > timeLimit){
                return true;
            }else{
                return false;
            }
        }

        public int getType(){
            return type;
        }

        public Sha256Hash getHash(){
            return hash;
        }
    }


    /**
     * A lekérendő tranzakciók, blokkok hash értékeit tároló osztály; sikeres és sikertelen lekérés
     * esetén is beállítja a következő lekérendő tranzakciót/blokkot.
     */
    public static class QueryQueue{

        // 0: lekért, 1: lekérendő
        private LinkedHashMap<Sha256Hash, Integer> linkedHashMap;

        public QueryQueue(){
            linkedHashMap = new LinkedHashMap<Sha256Hash, Integer>();
        }

        public synchronized boolean addHash(Sha256Hash hash){
            if(!linkedHashMap.containsKey(hash)){
                linkedHashMap.put(hash, 1);
                return true;
            }

            return false;
        }

        public synchronized Sha256Hash getNextHash(){
            for(Map.Entry<Sha256Hash, Integer> hash: linkedHashMap.entrySet()){
                if(hash.getValue() == 1){
                    // az adott hash értékhez tartozó tartalmat lekértnek jelöljük
                    hash.setValue(0);
                    return hash.getKey();
                }
            }

            return null;
        }


        private boolean setQueryStatus(Sha256Hash hash, int status){

            boolean isReplaced = false;

            if(linkedHashMap.containsKey(hash)){
                isReplaced = linkedHashMap.replace(hash, linkedHashMap.get(hash), status);
            }

            return isReplaced;
        }

        public synchronized boolean queryIsDone(Sha256Hash hash){
            System.out.println(hash + " le lett kérve.");
            return setQueryStatus(hash, 0);
        }

        public synchronized boolean queryIsFailed(Sha256Hash hash){
            System.out.println(hash + " újból lekérendő.");
            return setQueryStatus(hash, 1);
        }

        // minden lekérendő tartalmat lekértünk-e
        public synchronized boolean isDone(){
            return !linkedHashMap.containsValue(1);
        }

    }


}
