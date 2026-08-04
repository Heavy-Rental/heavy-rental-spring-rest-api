package com.heavy_rental.rest_api.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "asset_images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AssetImage {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "asset_id", nullable = false)
  private Asset asset;

  @Column(nullable = false, length = 255)
  private String imageUrl;

  @Column(name = "uploaded_at", nullable = false)
  private LocalDateTime uploadedAt;
}
