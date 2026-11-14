import java.io.FileInputStream;
import java.math.BigInteger;
import java.util.List;

import org.telegram.porcodio.PcWalletCore;

/** Test con vettori noti (solo chiavi di prova pubbliche, nessun fondo reale). Eseguito in CI. */
public class PcWalletCoreTest {
    static int fails = 0;

    static void eq(String name, String exp, String got) {
        if (!exp.equalsIgnoreCase(got)) {
            fails++;
            System.out.println("FAIL " + name + "\n  atteso:  " + exp + "\n  ottenuto: " + got);
        } else {
            System.out.println("ok   " + name);
        }
    }

    public static void main(String[] a) throws Exception {
        List<String> words = PcWalletCore.loadWords(new FileInputStream(a[0]));
        eq("wordlist size", "2048", String.valueOf(words.size()));
        // Keccak-256
        eq("keccak('')", "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", PcWalletCore.hex(PcWalletCore.keccak256(new byte[0])));
        eq("keccak('abc')", "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45", PcWalletCore.hex(PcWalletCore.keccak256("abc".getBytes())));
        // BIP39 (vettori ufficiali Trezor)
        String m0 = PcWalletCore.entropyToMnemonic(new byte[16], words);
        eq("bip39 entropy 00..", "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about", m0);
        eq("bip39 seed TREZOR", "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04",
            PcWalletCore.hex(PcWalletCore.mnemonicToSeed(m0, "TREZOR")));
        byte[] ff = new byte[16];
        java.util.Arrays.fill(ff, (byte) 0xff);
        eq("bip39 entropy ff..", "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong", PcWalletCore.entropyToMnemonic(ff, words));
        eq("bip39 valid", "true", String.valueOf(PcWalletCore.isValidMnemonic(m0, words)));
        eq("bip39 invalid checksum", "false", String.valueOf(PcWalletCore.isValidMnemonic("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon", words)));
        // BIP44 Ethereum (frase di test pubblica di Hardhat/Foundry)
        String junk = "test test test test test test test test test test test junk";
        byte[] priv = PcWalletCore.derivePrivate(PcWalletCore.mnemonicToSeed(junk, ""), PcWalletCore.ETH_PATH);
        eq("derive priv", "ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80", PcWalletCore.hex(priv));
        eq("address", "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266", PcWalletCore.addressFromPrivate(priv));
        eq("address valid", "true", String.valueOf(PcWalletCore.isValidAddress("0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266")));
        eq("address bad checksum", "false", String.valueOf(PcWalletCore.isValidAddress("0xf39fd6e51aad88F6F4ce6aB8827279cffFb92266")));
        // EIP-55 vettore ufficiale
        eq("eip55", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed", PcWalletCore.checksum("5aaeb6053f3e94c9b9a09f33669435e7ef1beaed"));
        // Firma EIP-155 (esempio ufficiale dell'EIP)
        byte[] k = PcWalletCore.unhex("4646464646464646464646464646464646464646464646464646464646464646");
        byte[][] tx = PcWalletCore.signLegacy(k, BigInteger.valueOf(9), BigInteger.valueOf(20000000000L), BigInteger.valueOf(21000),
            PcWalletCore.unhex("3535353535353535353535353535353535353535"), new BigInteger("1000000000000000000"), new byte[0], 1);
        eq("eip155 signing hash", "daf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53", PcWalletCore.hex(tx[0]));
        eq("eip155 raw tx", "f86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83",
            PcWalletCore.hex(tx[1]));
        // EIP-1559: struttura e determinismo
        byte[][] t2a = PcWalletCore.signEip1559(priv, 8453, BigInteger.ZERO, BigInteger.valueOf(1000000), BigInteger.valueOf(2000000), BigInteger.valueOf(21000),
            PcWalletCore.unhex("70997970C51812dc3A010C7d01b50e0d17dc79C8"), BigInteger.TEN, new byte[0]);
        byte[][] t2b = PcWalletCore.signEip1559(priv, 8453, BigInteger.ZERO, BigInteger.valueOf(1000000), BigInteger.valueOf(2000000), BigInteger.valueOf(21000),
            PcWalletCore.unhex("70997970C51812dc3A010C7d01b50e0d17dc79C8"), BigInteger.TEN, new byte[0]);
        eq("eip1559 deterministico", PcWalletCore.hex(t2a[1]), PcWalletCore.hex(t2b[1]));
        eq("eip1559 prefisso tipo 2", "02", PcWalletCore.hex(t2a[1]).substring(0, 2));
        // ERC-20
        eq("erc20 transfer data", "a9059cbb00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8000000000000000000000000000000000000000000000000000000000000000a",
            PcWalletCore.hex(PcWalletCore.erc20TransferData("0x70997970C51812dc3A010C7d01b50e0d17dc79C8", BigInteger.TEN)));
        eq("units", "1500000000000000", PcWalletCore.parseUnits("0.0015", 18).toString());
        eq("format", "0.0015", PcWalletCore.formatUnits(new BigInteger("1500000000000000"), 18, 6));
        // EIP-681
        String A = "0x52908400098527886E0F7030069857D2E4169EE7";
        PcWalletCore.Payment p1 = PcWalletCore.parsePayment(A);
        eq("eip681 plain", A, p1 == null ? "null" : p1.address);
        PcWalletCore.Payment p2 = PcWalletCore.parsePayment("ethereum:" + A + "@8453?value=2.5e18");
        eq("eip681 value", "2500000000000000000", p2 == null || p2.value == null ? "null" : p2.value.toString());
        eq("eip681 chain", "8453", p2 == null || p2.chainId == null ? "null" : p2.chainId.toString());
        PcWalletCore.Payment p3 = PcWalletCore.parsePayment("ethereum:0x8e23ee67d1332ad560396262c48ffbb01f93d052/transfer?address=" + A + "&uint256=1");
        eq("eip681 token to", A, p3 == null ? "null" : p3.address);
        eq("eip681 token contract", "0x8e23ee67d1332ad560396262c48ffbb01f93d052", p3 == null ? "null" : p3.contract);
        eq("eip681 pay-", A, PcWalletCore.parsePayment("ethereum:pay-" + A).address);
        eq("eip681 bad", "null", PcWalletCore.parsePayment("ethereum:0x123") == null ? "null" : "x");
        eq("eip681 not eth", "null", PcWalletCore.parsePayment("https://example.com") == null ? "null" : "x");
        if (fails > 0) {
            System.out.println(fails + " TEST FALLITI");
            System.exit(1);
        }
        System.out.println("TUTTI I TEST DEL PORTAFOGLIO OK");
    }
}
