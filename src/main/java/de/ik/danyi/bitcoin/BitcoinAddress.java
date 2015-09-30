package de.ik.danyi.bitcoin;

import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Base58;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.TestNet3Params;
import org.spongycastle.crypto.digests.RIPEMD160Digest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Érvényes Bitcoin címet reprezentáló osztály.
 *
 * Created by Imre Danyi on 2015.04.14..
 */
public class BitcoinAddress {
    private static final int EXTRACT_PREFIX = 0;
    private static final int EXTRACT_PAYLOAD = 1;
    private static final int EXTRACT_CHECKSUM = 2;

    // hálózattól függő (teszt-vagy éles hálózat) verzió prefix
    private final byte versionPrefix = (byte)App.netParams.getAddressHeader();

    // WIF formátum prefix
    private byte wifPrefix;

    /**
     * Privát kulcs visszaalakítása WIF formátumból.
     * @param wif
     * @return
     */
    public static byte[] privKeyFromWIF(String wif){
        // a privát kulcs 32 bájtos
        final int payloadLength = 32;

        // WIF formátum helyességének ellenőrzése
        if(!isWIFValid(wif)){
            System.out.println(String.format("%s nem érvényes WIF formátum.",
                    wif));
            return null;
        }

        return extract(EXTRACT_PAYLOAD, wif, payloadLength);
    }

    /**
     * Bitcoin cím vagy WIF formátum érvényességének ellenőrzésére használjuk.
     * @param src
     * @param payloadLength
     * @return
     */
    private static boolean isValid(String src, int payloadLength){

        // adatok kinyerése
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] prefix = extract(EXTRACT_PREFIX, src, payloadLength);
        byte[] payload = extract(EXTRACT_PAYLOAD, src, payloadLength);
        byte[] checksum = extract(EXTRACT_CHECKSUM, src, payloadLength);
        out.write(prefix, 0, prefix.length);
        out.write(payload, 0, payload.length);

        // checksum kiszámítása
        byte[] checksum2 = new byte[4];
        ByteArrayInputStream in = new ByteArrayInputStream(Utils.doubleSHA256(out.toByteArray()));
        try {
            in.read(checksum2, 0, 4);
            in.close();
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // a kinyert és a kiszámított checksum összehasonlítása
        for(int i=0; i<4; ++i){
            if(checksum[i] != checksum2[i]){
                return false;
            }
        }
        return true;

    }

    public static boolean isBitcoinAddressValid(String address){
        return isValid(address, 20);
    }

    public static boolean isWIFValid(String wif){
        return isValid(wif, 32);
    }

    /**
     * Meghatározott adatok kinyerése az adott forrásból. Bitcoin cím érvényességének
     * ellenőrzéséhez, valamint privát kulcs WIF formátumból történő visszaállításához,
     * WIF formátum helyességének ellenőrzéséhez használatos.
     *
     * @param extractType
     * @param src
     * @param payloadLength
     * @return
     */
    private static byte[] extract(int extractType, String src, int payloadLength){
        byte[] bytes = null;
        ByteArrayInputStream in;
        byte[] srcInBase58 = null;

        try {
            srcInBase58 = Base58.decode(src);
        } catch (AddressFormatException e) {
            e.printStackTrace();
        }

        // összetevő adatok kinyerése
        in = new ByteArrayInputStream(srcInBase58);
        try {
            switch (extractType) {
                case EXTRACT_PREFIX:
                    bytes = new byte[1];
                    in.read(bytes, 0, 1);
                    in.close();
                    break;
                case EXTRACT_PAYLOAD:
                    bytes = new byte[payloadLength];
                    in.skip(1);
                    in.read(bytes, 0, payloadLength);
                    in.close();
                    break;
                case EXTRACT_CHECKSUM:
                    bytes = new byte[4];
                    in.skip(payloadLength+1);
                    in.read(bytes, 0, 4);
                    in.close();
            }
        } catch (IOException e){
            e.printStackTrace();
        }

        return bytes;
    }

    private ECKey ecKey;
    private byte[] pubKeyHash;
    private String address;

    public BitcoinAddress() {
        this(new ECKey().getPrivKeyBytes());
    }

    public BitcoinAddress(byte[] privKey){
        ecKey = ECKey.fromPrivate(privKey, false);
        pubKeyHash = createPubKeyHash(ecKey);
        address = encodeBase58Check(versionPrefix, pubKeyHash);

        if(App.netParams.equals(MainNetParams.get())){
            wifPrefix = (byte)0x80;
        }
        else if(App.netParams.equals(TestNet3Params.get())){
            wifPrefix = (byte)0xef;
        }
    }

    public ECKey getEcKey(){
        return ecKey;
    }

    public byte[] getPubKeyHash(){
        return pubKeyHash;
    }

    public byte[] getPrivKey() {
        return ecKey.getPrivKeyBytes();
    }

    /**
     * Nyilvános kulcs hash értékének (PKH) előállítása publikus kulcsból.
     *
     * @return
     */
    private byte[] createPubKeyHash(ECKey ecKey){

        byte[] pubKey = ecKey.getPubKey();
        byte[] pkh = null;

        try {
            // SHA256 hashelés
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(pubKey, 0, pubKey.length);
            byte[] sha256Hash = digest.digest();

            // RIPEMD160 hashelés
            RIPEMD160Digest ripemd160Hash = new RIPEMD160Digest();
            ripemd160Hash.update(sha256Hash, 0, sha256Hash.length);

            // 20 bájtos kimenet előállítása
            pkh = new byte[20];
            ripemd160Hash.doFinal(pkh, 0);

        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }

        return pkh;
    }

    /**
     * PKH átalakítása a könnyebben olvasható Base58Check formába.
     *
     * @param versionPrefix
     * @param payload
     * @return
     */
    private String encodeBase58Check(byte versionPrefix, byte[] payload){

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // verzió prefix
        final byte[] version = new byte[1];
        version[0] = versionPrefix;

        // checksum előállítása
        byte[] checksum = new byte[4];

        out.write(version, 0, version.length);
        out.write(payload, 0, payload.length);

        ByteArrayInputStream in = new ByteArrayInputStream(Utils.doubleSHA256(out.toByteArray()));
        try {
            in.read(checksum, 0, 4);
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Base58 kódolás előtti bájttömb összeállítása
        out.reset();
        out.write(version, 0, version.length);
        out.write(payload, 0, payload.length);
        out.write(checksum, 0, checksum.length);
        byte[] preBase58 = out.toByteArray();

        try {
            out.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return Base58.encode(preBase58);
    }

    /**
     * Privát kulcs átalakítása WIF formátumba.
     * @return
     */
    public String convertToWIF(){

        // privát kulcs
        final byte[] privKey = getPrivKey();

        return encodeBase58Check(wifPrefix, privKey);
    }

    public String getAddress(){
        return address;
    }

    @Override
    public String toString() {
        return String.format("Bitcoin cím: %s", address.toString());
    }
}
