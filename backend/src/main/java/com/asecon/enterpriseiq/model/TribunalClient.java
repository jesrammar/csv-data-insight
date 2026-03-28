package com.asecon.enterpriseiq.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "tribunal_clients")
public class TribunalClient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "import_id")
    private TribunalImport tribunalImport;

    @ManyToOne(optional = false)
    @JoinColumn(name = "company_id")
    private Company company;

    @Column(name = "row_id")
    private Integer rowId;

    @Column(name = "tipo_cliente")
    private String tipoCliente;

    private String cliente;
    private String cif;
    private String administrador;

    @Column(name = "dni_nie")
    private String dniNie;

    private BigDecimal minutas;

    @Column(name = "f_alta")
    private LocalDate fAlta;

    @Column(name = "f_baja")
    private LocalDate fBaja;

    @Column(name = "f_pago")
    private String fPago;

    private String gestor;

    @Column(name = "cont_modelos")
    private Boolean contModelos;

    @Column(name = "is_irpf_ok")
    private Boolean isIrpfOk;

    @Column(name = "is_irpf_status")
    private String isIrpfStatus;

    @Column(name = "ddcc_ok")
    private Boolean ddccOk;

    @Column(name = "ddcc_status")
    private String ddccStatus;

    @Column(name = "libros_ok")
    private Boolean librosOk;

    @Column(name = "libros_status")
    private String librosStatus;

    @Column(name = "carga_de_trabajo")
    private BigDecimal cargaDeTrabajo;

    @Column(name = "pct_contabilidad")
    private BigDecimal pctContabilidad;

    private BigDecimal promedio;

    @OneToMany(mappedBy = "client", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TribunalActivity> activities = new ArrayList<>();

    public Long getId() { return id; }
    public TribunalImport getTribunalImport() { return tribunalImport; }
    public void setTribunalImport(TribunalImport tribunalImport) { this.tribunalImport = tribunalImport; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
    public Integer getRowId() { return rowId; }
    public void setRowId(Integer rowId) { this.rowId = rowId; }
    public String getTipoCliente() { return tipoCliente; }
    public void setTipoCliente(String tipoCliente) { this.tipoCliente = tipoCliente; }
    public String getCliente() { return cliente; }
    public void setCliente(String cliente) { this.cliente = cliente; }
    public String getCif() { return cif; }
    public void setCif(String cif) { this.cif = cif; }
    public String getAdministrador() { return administrador; }
    public void setAdministrador(String administrador) { this.administrador = administrador; }
    public String getDniNie() { return dniNie; }
    public void setDniNie(String dniNie) { this.dniNie = dniNie; }
    public BigDecimal getMinutas() { return minutas; }
    public void setMinutas(BigDecimal minutas) { this.minutas = minutas; }
    public LocalDate getFAlta() { return fAlta; }
    public void setFAlta(LocalDate fAlta) { this.fAlta = fAlta; }
    public LocalDate getFBaja() { return fBaja; }
    public void setFBaja(LocalDate fBaja) { this.fBaja = fBaja; }
    public String getFPago() { return fPago; }
    public void setFPago(String fPago) { this.fPago = fPago; }
    public String getGestor() { return gestor; }
    public void setGestor(String gestor) { this.gestor = gestor; }
    public Boolean getContModelos() { return contModelos; }
    public void setContModelos(Boolean contModelos) { this.contModelos = contModelos; }
    public Boolean getIsIrpfOk() { return isIrpfOk; }
    public void setIsIrpfOk(Boolean isIrpfOk) { this.isIrpfOk = isIrpfOk; }
    public String getIsIrpfStatus() { return isIrpfStatus; }
    public void setIsIrpfStatus(String isIrpfStatus) { this.isIrpfStatus = isIrpfStatus; }
    public Boolean getDdccOk() { return ddccOk; }
    public void setDdccOk(Boolean ddccOk) { this.ddccOk = ddccOk; }
    public String getDdccStatus() { return ddccStatus; }
    public void setDdccStatus(String ddccStatus) { this.ddccStatus = ddccStatus; }
    public Boolean getLibrosOk() { return librosOk; }
    public void setLibrosOk(Boolean librosOk) { this.librosOk = librosOk; }
    public String getLibrosStatus() { return librosStatus; }
    public void setLibrosStatus(String librosStatus) { this.librosStatus = librosStatus; }
    public BigDecimal getCargaDeTrabajo() { return cargaDeTrabajo; }
    public void setCargaDeTrabajo(BigDecimal cargaDeTrabajo) { this.cargaDeTrabajo = cargaDeTrabajo; }
    public BigDecimal getPctContabilidad() { return pctContabilidad; }
    public void setPctContabilidad(BigDecimal pctContabilidad) { this.pctContabilidad = pctContabilidad; }
    public BigDecimal getPromedio() { return promedio; }
    public void setPromedio(BigDecimal promedio) { this.promedio = promedio; }
    public List<TribunalActivity> getActivities() { return activities; }
    public void setActivities(List<TribunalActivity> activities) { this.activities = activities; }
}
