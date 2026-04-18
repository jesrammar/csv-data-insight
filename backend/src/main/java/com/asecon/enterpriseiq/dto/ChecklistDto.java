package com.asecon.enterpriseiq.dto;

import java.util.List;

public class ChecklistDto {
    private Long companyId;
    private String period;
    private List<ChecklistItemDto> items;

    public ChecklistDto() {}

    public ChecklistDto(Long companyId, String period, List<ChecklistItemDto> items) {
        this.companyId = companyId;
        this.period = period;
        this.items = items;
    }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public List<ChecklistItemDto> getItems() { return items; }
    public void setItems(List<ChecklistItemDto> items) { this.items = items; }

    public static class ChecklistItemDto {
        private String id;
        private String label;
        private boolean done;
        private String hint;
        private String actionHref;

        public ChecklistItemDto() {}

        public ChecklistItemDto(String id, String label, boolean done, String hint, String actionHref) {
            this.id = id;
            this.label = label;
            this.done = done;
            this.hint = hint;
            this.actionHref = actionHref;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public boolean isDone() { return done; }
        public void setDone(boolean done) { this.done = done; }
        public String getHint() { return hint; }
        public void setHint(String hint) { this.hint = hint; }
        public String getActionHref() { return actionHref; }
        public void setActionHref(String actionHref) { this.actionHref = actionHref; }
    }
}

