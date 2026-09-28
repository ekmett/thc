// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

/** Physical transport fields belong to this layout; loans have generation-bounded lifetime. */
public class HandoffStorage {
    private final HandoffLayout layout;
    public HandoffStorage(HandoffLayout layout) { this.layout = layout; }
    public HandoffLayout getLayout() { return layout; }
    private long generation = 0L;
    public long getGeneration() { return generation; }
    public void setGeneration(long value) { generation = value; }
    public long getGeneration$org_intelligence_thc() { return generation; }
    public void setGeneration$org_intelligence_thc(long value) { generation = value; }
    private boolean live = false;
    public boolean getLive() { return live; }
    public void setLive(boolean value) { live = value; }
    public boolean getLive$org_intelligence_thc() { return live; }
    public void setLive$org_intelligence_thc(boolean value) { live = value; }
    private long completedGeneration = -1L;
    public long getCompletedGeneration() { return completedGeneration; }
    public void setCompletedGeneration(long value) { completedGeneration = value; }
    public long getCompletedGeneration$org_intelligence_thc() { return completedGeneration; }
    public void setCompletedGeneration$org_intelligence_thc(long value) { completedGeneration = value; }
    private int inputMode = 0;
    public int getInputMode() { return inputMode; }
    public void setInputMode(int value) { inputMode = value; }
    public int getInputMode$org_intelligence_thc() { return inputMode; }
    public void setInputMode$org_intelligence_thc(int value) { inputMode = value; }
}
