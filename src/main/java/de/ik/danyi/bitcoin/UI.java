package de.ik.danyi.bitcoin;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

/**
 * Created by Imre Danyi on 2015.04.14..
 */
public class UI {

    JFrame window;
    JMenuBar mnBar;

    JMenu mnWallet;
    JMenuItem mnSaveWallet;
    JMenuItem mnOpenWallet;
    JMenuItem mnExit;

    JMenu mnTransaction;
    JMenuItem mnSend;

    JMenu mnAbout;
    private JPanel topPanel;
    private JLabel lblBalance;
    private JTextField txtBalance;
    private JLabel lblBtc;
    private JLabel lblReqAddr;
    private JTextField txtReqAddr;
    private JScrollPane scrollPane;
    private TxTable tblTx;
    private JPanel bottomPanel;
    private JLabel lblInfoLabel;
    private JButton btnNewReqAddr;
    private JPanel reqAddrPanel;
    private JPanel centerPanel;
    private JPanel panelTx;
    private JLabel lblPnlTx;
    private JComboBox comboTx;
    private JButton btnRefresh;
    private JPanel subTopPanel_1;
    private JPanel subTopPanel_2;
    private JSeparator separator_1;
    private JPanel infoPanel;
    private JPanel balancePanel;

    // éppen van-e felugró ablak
    private boolean isPopup = false;

    public UI(){

        // Op.rendszerre jellemző natív kinézet
        // -------------------------------------
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        // -------------------------------------

        window = new JFrame("Bitcoin tárcaprogram");
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        window.setIconImage(imageLoader("/images/BC_wallet_64b.png"));

        mnBar = new JMenuBar();
        mnBar.setFont(new Font("Segoe UI Semibold", Font.PLAIN, 13));
        mnBar.setPreferredSize(new Dimension(30, 30));
        window.setJMenuBar(mnBar);

        mnWallet = new JMenu("Tárca");
        mnWallet.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        mnBar.add(mnWallet);

        mnOpenWallet = new JMenuItem("Megnyitás");
        mnOpenWallet.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        mnOpenWallet.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setDialogTitle("Privát kulcsok importálása");
                int val = fileChooser.showOpenDialog(window);
                if (val == JFileChooser.APPROVE_OPTION) {
                    App.am.loadFromFile(fileChooser.getSelectedFile());
                }
            }
        });
        mnWallet.add(mnOpenWallet);

        mnSaveWallet = new JMenuItem("Mentés");
        mnSaveWallet.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        mnSaveWallet.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setDialogTitle("Privát kulcsok exportálása");
                int val = fileChooser.showSaveDialog(window);
                if (val == JFileChooser.APPROVE_OPTION) {
                    App.am.savePrivKeys(fileChooser.getSelectedFile());
                }
            }
        });
        mnWallet.add(mnSaveWallet);

        mnExit = new JMenuItem("Bezárás");
        mnExit.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        mnExit.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        });

        mnWallet.add(mnExit);

        mnTransaction = new JMenu("Tranzakció");
        mnTransaction.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        mnBar.add(mnTransaction);

        mnSend = new JMenuItem("Küldés");
        mnSend.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        mnSend.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // a tranzakció létrehozásához szükséges dialógus ablak összeállítása
                JPanel createTxPanel = new JPanel();
                createTxPanel.setLayout(new FlowLayout(FlowLayout.CENTER));

                // címzett
                JPanel dataPanel = new JPanel();
                dataPanel.add(new JLabel("Címzett:"));
                final JTextField addressTextField = new JTextField(30);
                dataPanel.add(addressTextField);

                // összeg
                String lblValue = String.format("Összeg (< %s BTC):",
                        App.tm.getBalance(App.minConfirmation).toPlainString());
                dataPanel.add(new JLabel(lblValue));

                final JTextField valueTextField = new JTextField(10);
                dataPanel.add(valueTextField);
                dataPanel.add(new JLabel("BTC"));
                createTxPanel.add(dataPanel);

                // információs sáv
                final JLabel lblInfo = new JLabel("");
                createTxPanel.add(lblInfo);

                final String btnSend = "Küldés";
                final String btnCancel = "Mégsem";
                Object[] options = {btnSend, btnCancel};

                final JOptionPane optionPane = new JOptionPane(
                        createTxPanel,
                        JOptionPane.PLAIN_MESSAGE,
                        JOptionPane.OK_CANCEL_OPTION,
                        null,
                        options,
                        options[0]);

                final JDialog dialog = new JDialog(
                        window,
                        "Bitcoin küldése",
                        true);

                dialog.setContentPane(optionPane);
                dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

                optionPane.addPropertyChangeListener(new PropertyChangeListener() {
                    @Override
                    public void propertyChange(PropertyChangeEvent e) {
                        String prop = e.getPropertyName();

                        if(dialog.isVisible()
                                && prop.equals(JOptionPane.VALUE_PROPERTY)
                                || prop.equals(JOptionPane.INPUT_VALUE_PROPERTY)){

                            Object value = optionPane.getValue();

                            if(value == JOptionPane.UNINITIALIZED_VALUE){
                                return;
                            }

                            // reset
                            optionPane.setValue(JOptionPane.UNINITIALIZED_VALUE);

                            // Mégsem
                            if(value.equals(btnCancel)){
                                addressTextField.setText(null);
                                valueTextField.setText(null);
                                dialog.setVisible(false);
                            }
                            // Küldés
                            else if(value.equals(btnSend)){
                                String toAddress = addressTextField.getText();

                                if(!isInputOK(toAddress)){
                                    lblInfo.setText("Nincs megadva cím.");
                                }else {
                                    // a megadott Bitcoin cím érvényes-e
                                    boolean isValid = BitcoinAddress.isBitcoinAddressValid(toAddress);

                                    // érvényes
                                    if (isValid) {
                                        // összeg ellenőrzése
                                        String valueInput = valueTextField.getText();

                                        if (!isInputOK(valueInput)) {
                                            lblInfo.setText("Nincs megadva összeg.");
                                        } else {

                                            double btcValue = 0f;
                                            try {
                                                btcValue = Double.parseDouble(valueInput);
                                                Coin coinValue = Coin.parseCoin(String.valueOf(btcValue));
                                                Coin available = App.tm.getBalance(App.minConfirmation);

                                                // megfelelő
                                                if (coinValue.isLessThan(available)
                                                        && coinValue.isGreaterThan(Transaction.MIN_NONDUST_OUTPUT)) {

                                                    Transaction tx =
                                                            App.tm.createTx(
                                                                    toAddress,
                                                                    coinValue,
                                                                    Coin.parseCoin("0"),
                                                                    App.minConfirmation);

                                                    // tranzakció elküldése az összes csomópontnak
                                                    if(tx != null) {
                                                        App.nm.sendMsgToAllNodes(tx);

                                                        showMessageForUser(
                                                                String.format("A létrehozott tranzakció:\n%s\nkiküldve a csomópontoknak.",
                                                                        tx.getHashAsString()), "Tranzakció kiküldve", false);

                                                        dialog.setVisible(false);

                                                    }

                                                } else {
                                                    lblInfo.setText("A megadott összeg túl magas vagy túl alacsony.");
                                                }

                                            }catch (NumberFormatException nfe){
                                                lblInfo.setText("A megadott összeg nem megfelelő formátumú.");
                                            }
                                        }
                                    }
                                    // nem érvényes
                                    else {
                                        lblInfo.setText("A megadott Bitcoin cím nem érvényes!");
                                    }
                                }
                            }
                        }
                    }
                });

                dialog.setMinimumSize(new Dimension(0, 150));
                dialog.pack();
                lblInfo.setText(String.format("Fontos! A program csak a legalább %d megerősítéssel rendelkező kimeneteket költi el.",
                        App.minConfirmation));
                dialog.setVisible(true);
            }
        });
        mnTransaction.add(mnSend);

        mnAbout = new JMenu("Súgó");
        mnAbout.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        mnAbout.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(MenuEvent e) {

                String message = "Bitcoin tárcaalkalmazás a Bitcoin teszthálózatán működve.\n";
                message += "A program a bitcoinj 0.12.3 kódkönyvtárat használja.\n\n";
                message += "A programhoz felhasznált képek forrása:\n";
                message += "Tárcakép: https://bitcointalk.org/index.php?topic=1631\n";
                message += "Piktogramok: http://www.entypo.com\n\n";
                message += "A programot készítette: Danyi Imre a szakdolgozati munka részeként.\n";
                message += "Témavezető: Prof. Dr. Pethő Attila\n\n";
                message += "Debrecen, 2015";

                JOptionPane.showMessageDialog(window, message, "Súgó", JOptionPane.INFORMATION_MESSAGE);
            }

            @Override
            public void menuDeselected(MenuEvent e) {

            }

            @Override
            public void menuCanceled(MenuEvent e) {

            }
        });

        mnBar.add(mnAbout);


        // TOP PANEL
        // ----------------------------------------------------------------------
        topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        window.getContentPane().add(topPanel, BorderLayout.NORTH);

        subTopPanel_1 = new JPanel();
        topPanel.add(subTopPanel_1);
        subTopPanel_1.setLayout(new BoxLayout(subTopPanel_1, BoxLayout.X_AXIS));

        balancePanel = new JPanel();
        balancePanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        subTopPanel_1.add(balancePanel);

        lblBalance = new JLabel("EGYENLEG:");
        balancePanel.add(lblBalance);
        lblBalance.setFont(new Font("Segoe UI", Font.BOLD, 13));

        txtBalance = new JTextField();
        balancePanel.add(txtBalance);
        txtBalance.setColumns(8);
        txtBalance.setText("0");
        txtBalance.setFont(new Font("Segoe UI", Font.BOLD, 13));
        txtBalance.setEditable(false);
        txtBalance.setBorder(null);
        txtBalance.setHorizontalAlignment(JTextField.RIGHT);

        lblBtc = new JLabel("BTC");
        balancePanel.add(lblBtc);
        lblBtc.setFont(new Font("Segoe UI", Font.BOLD, 13));

        reqAddrPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        subTopPanel_1.add(reqAddrPanel);
        ImageIcon icoReqAddr = new ImageIcon(imageLoader("/images/input.png"));
        lblReqAddr = new JLabel("Fogadó cím:", icoReqAddr , SwingConstants.CENTER);
        lblReqAddr.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        reqAddrPanel.add(lblReqAddr);

        txtReqAddr = new JTextField(30);
        txtReqAddr.setHorizontalAlignment(JTextField.CENTER);
        txtReqAddr.setEditable(false);
        txtReqAddr.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        txtReqAddr.setBorder(null);
        txtReqAddr.setColumns(35);
        reqAddrPanel.add(txtReqAddr);
        ImageIcon icoNewReqAddr = new ImageIcon(imageLoader("/images/plus.png"));
        btnNewReqAddr = new JButton("Új",icoNewReqAddr);
        btnNewReqAddr.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btnNewReqAddr.setToolTipText("Új cím generálása");
        reqAddrPanel.add(btnNewReqAddr);

        // új Bitcoin címet generálunk
        btnNewReqAddr.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                BitcoinAddress bitcoinAddress = App.am.createAddress(1).get(0);
                txtReqAddr.setText(bitcoinAddress.getAddress());
            }
        });

        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        topPanel.add(sep);

        subTopPanel_2 = new JPanel();
        topPanel.add(subTopPanel_2);

        panelTx = new JPanel();
        subTopPanel_2.add(panelTx);

        lblPnlTx = new JLabel("Tranzakciók:");
        lblPnlTx.setFont(new Font("Segoe UI", Font.BOLD, 13));
        panelTx.add(lblPnlTx);

        comboTx = new JComboBox();
        comboTx.setToolTipText("Válassz ki egy Bitcoin címet a kapcsolódó tranzakciók megtekintéséhez!");
        comboTx.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        comboTx.setModel(new DefaultComboBoxModel(new String[]{"Összes"}));

        // a lista aktuálisan kiválasztott Bitcoin címéhez kapcsolódó tranzakciók kilistázása
        comboTx.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (e.getStateChange() == ItemEvent.SELECTED) {
                    String item = comboTx.getSelectedItem().toString();

                    listOutsByItem(item);
                }
            }
        });

        panelTx.add(comboTx);

        // kimenetek frissítése
        ImageIcon icoRefresh = new ImageIcon(imageLoader("/images/refresh.png"));
        btnRefresh = new JButton(icoRefresh);
        btnRefresh.setFocusPainted(false);
        btnRefresh.setMargin(new Insets(0, 0, 0, 0));
        btnRefresh.setContentAreaFilled(false);
        btnRefresh.setBorderPainted(false);
        btnRefresh.setOpaque(false);
        btnRefresh.setToolTipText("Tranzakciólista frissítése");
        btnRefresh.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                Object selectedItem = null;
                String item = "";
                try {
                    selectedItem = comboTx.getSelectedItem();
                } catch (NullPointerException ne){
                    item = "Összes";
                }

                if(selectedItem != null) {
                    item = selectedItem.toString();
                }

                listOutsByItem(item);
            }
        });
        panelTx.add(btnRefresh);
        // ----------------------------------------------------------------------


        // CENTER PANEL
        // ----------------------------------------------------------------------
        centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        window.getContentPane().add(centerPanel, BorderLayout.CENTER);

        ArrayList<String> columnNames = new ArrayList<String>();
        columnNames.add("");
        columnNames.add("Irány");
        columnNames.add("Tranzakció");
        columnNames.add("Kapcsolódó saját cím");
        columnNames.add("Összeg (BTC)");
        columnNames.add("Megerősítések");
        tblTx = new TxTable(columnNames);

        scrollPane = new JScrollPane(tblTx);
        centerPanel.add(scrollPane);
        // ----------------------------------------------------------------------


        // BOTTOM PANEL
        // ----------------------------------------------------------------------
        bottomPanel = new JPanel();
        window.getContentPane().add(bottomPanel, BorderLayout.SOUTH);
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));

        separator_1 = new JSeparator();
        bottomPanel.add(separator_1);

        infoPanel = new JPanel();
        bottomPanel.add(infoPanel);

        lblInfoLabel = new JLabel("Csatlakozás a csomópontokhoz...");
        infoPanel.add(lblInfoLabel);
        lblInfoLabel.setHorizontalAlignment(SwingConstants.LEFT);
        lblInfoLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        // ----------------------------------------------------------------------

        window.setMinimumSize(new Dimension(870,300));
        window.pack();
        window.setVisible(true);
    }
    
    private Image imageLoader(String image){
    	Image img = null;
    	try{
    		img = ImageIO.read(getClass().getResource(image));	
    	} catch (IOException e){
    		System.out.println("A kép betöltése nem sikerült: " + image);
    	}
    	
    	return img;
    }

    private boolean isInputOK(String input){
        if(input == null || input.equals("")){
            return false;
        }else{
            return true;
        }
    }

    private void listOutsByItem(String item){

        java.util.List<TransactionManager.OutInfo> outs = null;

        if (item.equals("Összes")) {
            txtBalance.setText(App.tm.getBalance(0).toPlainString());
            outs = App.tm.getRelevantOuts();

        }
        else if(item != null || !item.equals("")) {
            BitcoinAddress selectedBitcoinAddress = App.am.getAddress(item);

            if(selectedBitcoinAddress != null) {
                txtReqAddr.setText(selectedBitcoinAddress.getAddress());

                // az egyenleg értéket a kiválasztott cím szerint kérjük le
                txtBalance.setText(App.tm.getBalanceByAddr(selectedBitcoinAddress, 0).toPlainString());
                outs = App.tm.getOutsByAddress(selectedBitcoinAddress.getAddress());
            }
        }

        // az adott címhez/címekhez tartozó elköltött és elköltetlen kimeneteket kilistázzuk
        if(outs != null){

            // előző sorok törlése a táblázatból
            ((DefaultTableModel)tblTx.getModel()).setRowCount(0);

            for(TransactionManager.OutInfo outInfo: outs) {
                // lekérjük a kiválasztott címhez tartozó kimeneteket
                putOutInToTable(outInfo);
            }

        }
    }

    /**
     * A kiválasztott (vagy az összes) címhez tartozó releváns kimenetek listázza ki.
     *
     * @param outInfo
     */
    public void putOutInToTable(TransactionManager.OutInfo outInfo){

        if(outInfo.getOutput() == null){
            return;
        }

        String type = "";     // 'Érkezett' vagy 'Küldött'
        String txHash = "";
        String address = outInfo.getAddress();
        String value = "";
        String confirms = "";

        int outType = TransactionManager.OutInfo.OUTPUT;
        Transaction outInfoTx = null;
        while(true){

            if(outType == TransactionManager.OutInfo.OUTPUT){
                TransactionOutput output = outInfo.getOutput();
                outInfoTx = output.getParentTransaction();
                type = "Érkezett";
                value = output.getValue().toPlainString();
            }
            else if(outType == TransactionManager.OutInfo.INPUT){
                TransactionInput input = outInfo.getInput();
                outInfoTx = input.getParentTransaction();
                type = "Küldött";
                value = input.getValue().toPlainString();
            }

            // tranzakció hash értéke
            txHash = outInfoTx.getHashAsString();

            // megerősítés
            confirms = String.valueOf(outInfo.getConfirmations(outType));

            // a kinyert adatok beillesztése a táblázatba
            int rowCount = tblTx.getRowCount();
            tblTx.addRow(String.valueOf(++rowCount), type, txHash, address, value, confirms);

            if(outType == TransactionManager.OutInfo.OUTPUT && outInfo.isSpent()){
                // a következő körben az elköltött kimenet beágyazottságát vizsgáljuk
                outType = TransactionManager.OutInfo.INPUT;
            }else{
                break;
            }
        }
    }


    public void addAddrToList(String address){
        comboTx.addItem(address);
    }

    public void removeAddrFromList(String address){
        comboTx.removeItem(address);
    }

    /**
     * Frissíti a blokkláncszinkronizáció állapotát.
     *
     * @param status
     */
    public void printSyncStatus(String status){
        lblInfoLabel.setText(status);
    }


    /**
     * Tájékoztató üzenet megjelenítése a felhasználó számára.
     *
     * @param message
     * @param title
     * @param isBlocking
     */
    public void showMessageForUser(String message, String title, boolean isBlocking){
        if(!isPopup) {
            // az aktuális üzenet megjelenítése alatt nem jelenik meg több popup üzenet
            if(isBlocking){
                isPopup = true;
            }

            int result = JOptionPane.showConfirmDialog(window, message, title, JOptionPane.DEFAULT_OPTION);

            if(result == JOptionPane.OK_OPTION){
                isPopup = false;
            }
        }
    }
}
