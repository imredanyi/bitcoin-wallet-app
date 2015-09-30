package de.ik.danyi.bitcoin;

import javax.swing.*;
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;

/**
 * Created by Imre Danyi on 2015.04.25..
 */
public class TxTable extends JTable{

    private DefaultTableModel tableModel;
    private TableModelEvent tMEvent;
    private ArrayList<String> columnNames;

    public TxTable(ArrayList<String> columnNames) {
        super();

        this.columnNames = columnNames;

        tableModel = new DefaultTableModel(null, columnNames.toArray());
        this.setModel(tableModel);

        // a táblázat minden "cellájához" egy alapértelmezett renderelést állítunk be
        this.setDefaultRenderer(Object.class, new DefaultTableCellRenderer(){

            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column){
                setBackground(null);
                setHorizontalAlignment(SwingConstants.CENTER);

                // minden páratlan sorszámú sor sötétebb hátteret kap
                if(row % 2 != 0){
                    setBackground(Color.lightGray);
                }else{
                    setBackground(Color.white);
                }

                table.setRowHeight(20);

                table.setEnabled(false);
                table.setRowSelectionAllowed(false);
                table.setColumnSelectionAllowed(false);
                table.setFont(new Font("Segoe UI", Font.PLAIN, 12));

                return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            }

        });

        this.getTableHeader().setDefaultRenderer(getNewTableCellRenderer(Color.darkGray, Color.white));

    }

    private DefaultTableCellRenderer getNewTableCellRenderer(Color bgColor, Color fgColor){
        DefaultTableCellRenderer tableCellRenderer = new DefaultTableCellRenderer();
        tableCellRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        tableCellRenderer.setBackground(bgColor);
        tableCellRenderer.setForeground(fgColor);
        return tableCellRenderer;
    }

    public void addRow(String[] rowData){
        tableModel.addRow(rowData);
    }

    public void addRow(String index, String txType, String txHash, String address, String value, String confirms){
        String[] row = new String[]{index, txType, txHash, address, value, confirms};
        addRow(row);
    }

}
