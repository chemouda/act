package com.act.biointerpretation.operators;

import chemaxon.common.util.Pair;
import com.act.biointerpretation.utils.ChemAxonUtils;
import com.act.biointerpretation.operators.OperatorHasher.Operator;

import java.util.*;

/**
 * Compares two OperatorHashers, one from BRENDA to one from Metacyc
 * Created by jca20n on 11/11/15.
 */
public class CompareROs {
    OperatorHasher brendaHash;
    OperatorHasher metacycHash;

    public static void main(String[] args) throws Exception  {
        CompareROs comp = new CompareROs();
        comp.initiate();
        Map<Pair<String,String>, Integer> counts = comp.compare();
        Set<Integer> rxnIds = comp.getGoodRxnIds(counts);

        System.out.println(rxnIds.size());
    }

    public void initiate() throws Exception {
        brendaHash = OperatorHasher.deserialize("output/brenda_hash_ero.ser");
        metacycHash = OperatorHasher.deserialize("output/metacyc_hash_ero.ser");
    }

    public Set<Integer> getGoodRxnIds(Map<Pair<String,String>, Integer> combined) {
        Set<Integer> out = new HashSet<>();

        Map<Pair<String,String>, Set<Operator>> brendaOps = brendaHash.reduceToOperators();
        Map<Pair<String,String>, Set<Operator>> metacycOps = metacycHash.reduceToOperators();

        for(Pair<String,String> pair : combined.keySet()) {
            Set<Operator> Bops = brendaOps.get(pair);
            Set<Operator> Mops = metacycOps.get(pair);

            if(Bops!=null) {
                for(Operator op : Bops) {
                    out.addAll(op.rxnIds);
                }
            }
            if(Mops!=null) {
                for(Operator op : Mops) {
                    out.addAll(op.rxnIds);
                }
            }
        }
        return out;
    }

    public Map<Pair<String,String>, Integer> compare() throws Exception {
        Map<Pair<String,String>, Integer> brendaPairs = brendaHash.reduceToCounts();
        Map<Pair<String,String>, Integer> metacycPairs = metacycHash.reduceToCounts();

        Map<Pair<String,String>, Integer> combined = new HashMap<>();

        for(Pair<String,String> apair : brendaPairs.keySet()) {
            int bval = brendaPairs.get(apair);

            if(metacycPairs.containsKey(apair)) {
                int mval = metacycPairs.get(apair);
                if(bval<2 || mval<2) {
                    continue;
                }
                bval += mval;
            } else {
                if(bval < 10) {
                    continue;
                }
            }

            combined.put(apair, bval);
        }

        for(Pair<String,String> apair : metacycPairs.keySet()) {
            int mval = metacycPairs.get(apair);
            if(brendaPairs.containsKey(apair)) {
                //Such entries were already included in previous operation
                continue;
            }
            if(mval < 10) {
                continue;
            }
            combined.put(apair, mval);
        }

        List<Map.Entry<Pair<String,String>, Integer>> ranked = OperatorHasher.rank(combined);

        for(Map.Entry<Pair<String,String>, Integer> entry : ranked) {
            int count = entry.getValue();
            Pair<String,String> pair = entry.getKey();

            String subSmiles = ChemAxonUtils.InchiToSmiles(pair.left());
            String prodSmiles = ChemAxonUtils.InchiToSmiles(pair.right());

//            System.out.println(subSmiles+ " >> " + prodSmiles + " : " + count);
        }

        System.out.println("Total ERO: " + ranked.size());
        return combined;
    }

}
