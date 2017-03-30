/*
 * Copyright (c) 2016, Gaetano Pellegrino
 *
 * This program is released under the GNU General Public License
 * Info online: http://www.gnu.org/licenses/quick-guide-gplv3.html
 * Or in the file: LICENSE
 * For information/questions contact: gllpellegrino@gmail.com
 */

package RAI;

import RAI.nnstrategy.*;
import RAI.transition_clustering.Transition;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


// In questa versione sono apportati cambi NON ancora concettuali ma di riorganizzazione del codice.
// Principali cambiamenti:
// 1) Hypothesis ora contiene solo l'algoritmo di learning. Ciò vuol dire che promote, fold, ecc sono spostate in State
// 2) State ha maggior responsabilità, contiene cose che prima erano appannaggio di Hypothesis


public class Hypothesis <T extends Data<T>>{


    public Hypothesis(DataBuilder<T> dataBuilder){
        this(dataBuilder, 0);
    }

    public Hypothesis(DataBuilder<T> dataBuilder, int maxTransitions){
        this();
        State.MAX_TRANSITIONS = maxTransitions;
        this.dataBuilder = dataBuilder;
        root = new State<>(this, dataBuilder.createInstance());
        root.setRoot();
    }

    public Hypothesis(){
        State.MAX_TRANSITIONS = 0;
        dataBuilder = null;
        root = null;
        allowedMerges = new HashSet<>();
        redStates = new HashSet<>();
        //blueStates = new HashSet<>();
        blueStates = new LinkedList<>();
    }

    public void setMinTransitions(int minTransitions){
        State.MAX_TRANSITIONS = minTransitions;
    }

    private static String[] suffix(String[] s, int i){
        String[] res = new String[s.length - i];
        int j = 0;
        for (int k =  0; k < s.length; k++){
            if (k >= i){
                res[j] = s[k];
                j += 1;
            }
        }
        return res;
    }

    private void prefixTree(String trainingPath){
        try (BufferedReader br = new BufferedReader(new FileReader(trainingPath))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] values = line.split(" ");
                State<T> state = root;
                for (int i = 0; i < values.length; i++){
//                    OLD BODY OF THE WHOLE FOR
//                    double value = new Double(values[i]);
//                    if (state.getOutgoing(value) == null) {
//                        State<T> son = new State<>(this, dataBuilder.createInstance());
//                        Transition<T> newT = new Transition<>(state, son , value);
//                        state.addOutgoing(newT);
//                        newT.getDestination().addIngoing(newT);
//                    }
//                    state.getData().add(suffix(values, i));
//                    // a questo punto ci sarà sicuro la transizione uscente per value
//                    state = state.getOutgoing(value).getDestination();
                    double value = new Double(values[i]);
                    State<T> son = new State<>(this, dataBuilder.createInstance());
                    Transition<T> newT = new Transition<>(state, son , value);
                    state.addOutgoing(newT);
                    newT.getDestination().addIngoing(newT);
                    state.getData().add(suffix(values, i));
                    state = son;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void fromDOT(String modelPath){
        // READ A MODEL FROM A DOT FILE
        try (BufferedReader br = new BufferedReader(new FileReader(modelPath))) {
            String line;
            Map<Integer, State<T>> states = new HashMap<>();
            State<T> current;
            while ((line = br.readLine()) != null) {
                Matcher lMatcher = stateRE.matcher(line);
                if (lMatcher.matches()){
                    //state id and mu
                    Integer sid = Integer.parseInt(lMatcher.group("sid"));
                    if (! states.containsKey(sid))
                        states.put(sid, new State<>(this, null, sid));
                    current = states.get(sid);
                    if (sid.equals(0))
                        root = current;
                } else {
                    lMatcher = transRE.matcher(line);
                    if (lMatcher.matches()){
                        Integer ssid = Integer.parseInt(lMatcher.group("ssid"));
                        Integer dsid = Integer.parseInt(lMatcher.group("dsid"));
                        Double lguard = Double.parseDouble(lMatcher.group("lguard"));
                        Double rguard = Double.parseDouble(lMatcher.group("rguard"));
                        Double mu = Double.parseDouble(lMatcher.group("mu"));
                        //System.out.println("BUM " + ssid + " " + dsid + " " + lguard + " " + rguard);
                        if (! states.containsKey(dsid))
                            states.put(dsid, new State<>(this, null, dsid));
                        current = states.get(ssid);
                        Transition<T> t = new Transition<>(current, states.get(dsid), lguard, rguard, mu);
                        current.addOutgoing(t);
                        t.getDestination().addIngoing(t);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // CANDIDATE MERGES STUFF

    public void notifyPromotion(State<T> s){
        // it gets called before updating s's fields as a consequence of the promotion
        if (s.isBlue()) {
            redStates.add(s);
            blueStates.remove(s);
            // flushing possible couples where s plays the blue role
            Iterator<CandidateMerge> pairs = s.getMergesIterator();
            while (pairs.hasNext()){
                CandidateMerge pair = pairs.next();
                System.out.println("Popping " + pair);
                pairs.remove();
                allowedMerges.remove(pair);
            }
            // adding new couples where s plays the red role
            for (State<T> blueState : blueStates) {
                CandidateMerge<T> pair = new CandidateMerge<>(s, blueState);
                //if (s.getData().isCompatibleWith(blueState.getData())){
                    System.out.println("Pushing " + pair);
                    s.addMerge(pair);
                    allowedMerges.add(pair);
                //}
            }
        }else if (s.isWhite()) {
            blueStates.addLast(s);
            for (State<T> redState : redStates) {
                CandidateMerge<T> pair = new CandidateMerge<>(redState, s);
                //if (redState.getData().isCompatibleWith(s.getData())) {
                    System.out.println("Pushing " + pair);
                    s.addMerge(pair);
                    allowedMerges.add(pair);
                //}
            }
        }
    }

    public void notifyDisposal(State<T> s){
        for (State<T> red : redStates) {
            CandidateMerge<T> pair = new CandidateMerge<>(red, s);
            //System.out.println("Popping " + pair);
            allowedMerges.remove(pair);
        }
        blueStates.remove(s);
    }

    // END OF CANDIDATE MERGES STUFF

    public void minimize(String samplePath){
        prefixTree(samplePath);
        root.promote().promote();
        System.out.println("Prefix Tree created");
        while (true){
            CandidateMerge<T> pair = chooseBestMerge();
            if (pair == null)
                break;
            System.out.println("Considering couple " + pair);
            State<T> rs = pair.getRedState();
            State<T> bs = pair.getBlueState();
            if (rs.getId() == 0 && bs.getId() == 1662)
                System.out.println("canc");
            if (rs.getData().isCompatibleWith(bs.getData()))
                rs.mergeWith(bs);
            else {
                System.out.println("Discarding " + pair);
                bs.removeMerge(pair);
                if (! bs.hasMerges())
                    bs.promote();
            }
        }
        System.out.println("States: " + redStates.size());
    }


    private CandidateMerge<T> chooseBestMerge(){
        //Double bestScore = Double.POSITIVE_INFINITY;
        Double bestScore = Double.NEGATIVE_INFINITY;
        CandidateMerge<T> bestMerge = null;
        for (CandidateMerge<T> c : allowedMerges){
            State<T> red = c.getRedState();
            State<T> blue = c.getBlueState();
            //Double score = red.getData().rankWith(blue.getData());
            T dr = red.getData();
            T db = blue.getData();
            Double score = dr.rankWith(db);
            //System.out.println("CANC ME " + score);
            //System.out.println("Evaluated merge between <RED " + red.getId() + "> and <BLUE " + blue.getId() + "> with score " + score);
            if (score > bestScore){
            //if (score < bestScore){
                bestMerge = c;
                bestScore = score;
            }
        }
        if (bestMerge != null)
            System.out.println("Popping " + bestMerge);
        allowedMerges.remove(bestMerge);
        return bestMerge;
    }

    public Hypothesis<T> expand(){
        root.expand();
        return this;
    }

    public State<T> getRoot(){
        return root;
    }

    public void toDot(String path){
        try {
            Set<State<T>> visited = new HashSet<>();
            LinkedList<State<T>> toVisit = new LinkedList<>();
            toVisit.addFirst(root);
            FileWriter writer = new FileWriter(path, false);
            writer.write("digraph DFA {\nrankdir=LR");
            while (! toVisit.isEmpty()) {
                State<T> s = toVisit.removeFirst();
                if (! visited.contains(s)) {
                    visited.add(s);
                    writer.write("\n" + s.toDot());
                    Iterator<Transition<T>> iterator = s.getOutgoingIterator();
                    while (iterator.hasNext()) {
                        Transition<T> t = iterator.next();
                        State<T> next = t.getDestination();
                        if ((next != null) && (!visited.contains(next)))
                            toVisit.addFirst(next);
                    }
                }
            }
            writer.write("\n}");
            writer.close();
        } catch (IOException o){
            o.printStackTrace();
        }
    }


    private State<T> root;
    private Set<State<T>> redStates;
    private LinkedList<State<T>> blueStates;
    private Set<CandidateMerge<T>> allowedMerges;
    private DataBuilder<T> dataBuilder;
    private static final Pattern stateRE = Pattern.compile(
            "^(?<sid>\\d+) \\[shape=(circle|doublecircle), label=\\\"\\d+\\\"\\];$");
    private static final Pattern transRE = Pattern.compile(
            "^\\t(?<ssid>\\d+) -> (?<dsid>\\d+) \\[label=\"(\\[|\\])(?<lguard>(-?\\d*\\.?\\d+)|(-Infinity)), " +
                    "(?<rguard>(-?\\d*.?\\d+)|(Infinity))(\\]|\\[) (?<mu>-?\\d*\\.?\\d+)\\\"\\];$");

}
